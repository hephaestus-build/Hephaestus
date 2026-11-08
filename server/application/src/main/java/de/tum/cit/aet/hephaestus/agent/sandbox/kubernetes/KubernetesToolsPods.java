package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxGitHardening;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conversation tools Pods as Jobs in the sandbox namespace. Each runs the tools program of tool protocol v1 on TCP,
 * with a fresh nonce in its spec, and its host dials it at the Pod address. The worker only creates, lists and deletes
 * them: it has no {@code pods/exec}.
 */
public class KubernetesToolsPods implements ToolsPods {

    private static final Logger log = LoggerFactory.getLogger(KubernetesToolsPods.class);

    private static final String HOSTNAME = "tools";

    /** How long a tools Pod may take from its Job's create until its tools program runs: scheduling and the image. */
    private static final Duration START_TIME = Duration.ofMinutes(2);

    private final KubernetesPods pods;
    private final KubernetesSandboxJobs jobs;
    private final KubernetesWorker worker;
    private final String image;
    private final SandboxExits exits;
    private final KubernetesSandboxCache cache;
    private final Clock clock;
    private final ReentrantLock quotaAdmission = new ReentrantLock();
    private @Nullable Long quotaRefusedAt;

    public KubernetesToolsPods(
            KubernetesPods pods,
            KubernetesSandboxJobs jobs,
            String image,
            SandboxExits exits,
            KubernetesSandboxCache cache,
            Clock clock) {
        this.pods = pods;
        this.jobs = jobs;
        this.worker = jobs.worker();
        this.image = image;
        this.exits = exits;
        this.cache = cache;
        this.clock = clock;
    }

    @Override
    public boolean ready() {
        return worker.isolated();
    }

    @Override
    public Started create(String sandboxId, String nonce, ResourceLimits limits) {
        if (!sandboxId.matches(KubernetesSandboxProperties.DNS_LABEL)) {
            throw new IllegalArgumentException("A tools Pod id must be a DNS label");
        }
        worker.requireIsolation();
        String name = ToolsPods.name(sandboxId);
        Map<String, String> labels = worker.labels(KubernetesWorker.KIND_TOOLS);
        labels.put(KubernetesWorker.SANDBOX_ID, sandboxId);
        Pod template = pods.toolsPod(
                new KubernetesPods.Workload(name, labels, image, HOSTNAME),
                limits,
                nonce,
                SandboxGitHardening.environment());
        admit(template, jobs.job(template, limits.maxRuntime().toSeconds()));
        try {
            Pod created = jobs.awaitPod(name, START_TIME, () -> false);
            KubernetesPods.Waited started = pods.waitFor(
                    created.getMetadata().getName(),
                    candidate -> running(candidate)
                            || KubernetesPods.terminal(candidate)
                            || KubernetesPods.fatalWaiting(candidate) != null,
                    START_TIME);
            Pod pod = started.pod();
            if (started.timedOut() || pod == null || !running(pod)) {
                String waiting = pod == null ? null : KubernetesPods.fatalWaiting(pod);
                throw new SandboxInfrastructureException(
                        "Could not start a tools Pod" + (waiting == null ? "" : ": " + waiting));
            }
            return new Started(name, pod.getStatus().getPodIP(), PORT, nonce);
        } catch (SandboxException e) {
            remove(name);
            throw e;
        }
    }

    /** A current cache refuses early; the API server decides every create that is sent. */
    private void admit(Pod template, Job job) {
        quotaAdmission.lock();
        try {
            Long refused = quotaRefusedAt;
            if (refused != null && System.nanoTime() - refused < TimeUnit.SECONDS.toNanos(1)) {
                throw new SandboxCapacityException(
                        "The tools quota has no room", SandboxCapacityException.Reason.QUOTA_EXCEEDED);
            }
            try {
                checkQuota(template);
                jobs.create(job);
                quotaRefusedAt = null;
            } catch (SandboxCapacityException full) {
                if (full.reason() == SandboxCapacityException.Reason.QUOTA_EXCEEDED) quotaRefusedAt = System.nanoTime();
                throw full;
            }
        } finally {
            quotaAdmission.unlock();
        }
    }

    private void checkQuota(Pod pod) {
        List<ResourceQuota> quotas;
        Map<String, Quantity> overhead = Map.of();
        try {
            if (!cache.current()) return;
            quotas = cache.quotas();
            String runtimeClass = pod.getSpec().getRuntimeClassName();
            if (runtimeClass != null) {
                for (Pod observed : cache.pods(Map.of())) {
                    if (observed.getSpec() != null
                            && runtimeClass.equals(observed.getSpec().getRuntimeClassName())
                            && observed.getSpec().getOverhead() != null) {
                        overhead = observed.getSpec().getOverhead();
                        break;
                    }
                }
            }
        } catch (RuntimeException unreadable) {
            // A stale or unreadable cache cannot decide admission; the Job's Pod below still can.
            return;
        }
        var footprint = KubernetesSandboxQuota.footprint(pod, overhead);
        String priority = Objects.toString(pod.getSpec().getPriorityClassName(), "");
        if (KubernetesSandboxQuota.neverFits(quotas, footprint, priority)) {
            throw new SandboxCapacityException(
                    "The tools quota cannot hold one tools Pod", SandboxCapacityException.Reason.QUOTA_CANNOT_FIT);
        }
        if (KubernetesSandboxQuota.fits(quotas, footprint, priority) == 0) {
            throw new SandboxCapacityException(
                    "The tools quota has no room", SandboxCapacityException.Reason.QUOTA_EXCEEDED);
        }
    }

    /** Whether the tools program runs, with an address that its host can dial. */
    private static boolean running(Pod pod) {
        return KubernetesPods.running(pod, KubernetesPods.TOOLS)
                && pod.getStatus() != null
                && pod.getStatus().getPodIP() != null
                && !pod.getStatus().getPodIP().isEmpty();
    }

    @Override
    public void remove(String id) {
        // Before the delete: the Pod's exit code and log go with it.
        try {
            Pod pod = jobs.podOf(id);
            if (pod != null) {
                exits.record(SandboxExits.Kind.TOOLS, pod.getMetadata().getName());
            }
            jobs.deleteWithPod(id);
        } catch (SandboxException e) {
            log.warn("Could not remove tools Pod {}: {}", id, e.getClass().getSimpleName());
        }
    }

    /** The tools Jobs of the installation, whichever worker created them. */
    @Override
    public List<Listed> list() {
        Instant now = clock.instant();
        return jobs.list(KubernetesWorker.KIND_TOOLS).stream()
                .map(job -> {
                    String created = job.getMetadata().getCreationTimestamp();
                    Duration age = created == null ? Duration.ZERO : Duration.between(Instant.parse(created), now);
                    return new Listed(job.getMetadata().getName(), age);
                })
                .toList();
    }
}
