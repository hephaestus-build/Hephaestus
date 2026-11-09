package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxRoom;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandboxes in the sandbox namespace: a practice review attempt as a {@code batch/v1} Job ({@link KubernetesSandboxJobs}).
 * Its Pod is the one of {@link KubernetesPods#workspacePod}: the isolation gate, the workspace initializer, then the
 * runtime with {@code /workspace} read-only. The chart's policy lets it reach the gateway port of the worker Pods and
 * nothing else.
 *
 * <p>A handle is the Job's name, so any worker reaches a sandbox that another one created. Each sandbox ends without its
 * worker: at the attempt's upload deadline, which the TTL controller then removes with its Pod. {@code
 * SandboxReconciler} removes what remains.
 */
public class KubernetesSandboxBackend implements SandboxBackend, SandboxRoom {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxBackend.class);

    /** Beyond the upload deadline: the Job controller stops an attempt whose worker no longer does. */
    static final Duration DEADLINE_BACKSTOP = Duration.ofMinutes(2);

    private static final String REVIEW_PREFIX = "hephaestus-review-";

    private final KubernetesPods pods;
    private final KubernetesSandboxJobs reviewJobs;
    private final KubernetesWorker worker;
    private final SandboxExits exits;
    private final KubernetesSandboxQuota quota;
    private final KubernetesSandboxCache cache;
    private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();

    public KubernetesSandboxBackend(
            KubernetesPods pods,
            KubernetesSandboxJobs reviewJobs,
            KubernetesWorker worker,
            SandboxExits exits,
            KubernetesSandboxCache cache) {
        this.pods = pods;
        this.reviewJobs = reviewJobs;
        this.worker = worker;
        this.exits = exits;
        this.quota = new KubernetesSandboxQuota(
                cache,
                worker.namespace(),
                worker.properties().runtimeClassName(),
                worker.properties().reviewPriorityClass());
        this.cache = cache;
    }

    /** A sandbox that this process launched: its Job or Pod, and the Pod once a review's Job has created it. */
    private static final class Sandbox {
        private final String name;
        private volatile @Nullable String pod;
        private volatile boolean killed;
        /** Whether its exit is recorded: once, before the first deletion of its Job. */
        private final AtomicBoolean recorded = new AtomicBoolean();
        /** The transcripts read before a stop at a stage's deadline, which deletes the Pod with its logs. */
        private final Map<Container, String> kept = new ConcurrentHashMap<>();

        private Sandbox(String name, @Nullable String pod) {
            this.name = name;
            this.pod = pod;
        }
    }

    /** The isolation probe's latest proof, which the worker's readiness also reports. */
    @Override
    public boolean ready() {
        return worker.isolated();
    }

    /** The quota counts a review's Pod once the Job controller created it; a launched Job without one takes room too. */
    @Override
    public int roomFor(ResourceLimits limits) {
        int room = quota.roomFor(limits);
        if (room == Integer.MAX_VALUE || room == SandboxRoom.UNKNOWN_ROOM) return room;
        long launching = sandboxes.values().stream()
                .filter(sandbox -> sandbox.pod == null)
                .count();
        return (int) Math.max(0, room - launching);
    }

    @Override
    public boolean neverFits(ResourceLimits limits) {
        return quota.neverFits(limits);
    }

    /** A review Job with an active Pod, of any worker; a Job whose Pod the quota refused has none. */
    @Override
    public boolean reviewsRunning() {
        try {
            return cache.jobs(worker.selector(KubernetesWorker.KIND_REVIEW)).stream()
                    .anyMatch(job -> job.getStatus() != null
                            && job.getStatus().getActive() != null
                            && job.getStatus().getActive() > 0);
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public Handle launch(Launch launch) {
        worker.requireIsolation();
        Map<String, String> labels = worker.labels(KubernetesWorker.KIND_REVIEW);
        labels.put(KubernetesWorker.JOB_ID, launch.id().toString());
        if (launch.attempt() != null) {
            labels.put(KubernetesWorker.JOB_ATTEMPT, launch.attempt().toString());
        }
        String name = jobName(launch.id(), launch.attempt());
        Pod pod = pods.workspacePod(
                new KubernetesPods.Workload(name, labels, launch.image(), "agent"),
                launch.environment().at(worker.gatewayUrl()),
                launch.command(),
                launch.limits(),
                KubernetesPods.REVIEW,
                new KubernetesPods.Sidecars(UUID.randomUUID().toString(), launch.programs()));
        Instant end = launch.deadline();
        if (end == null) {
            throw new SandboxException("A practice review needs its upload deadline");
        }
        // Counted from the Job's start, which is after now: the bound is never earlier than the attempt's own.
        long deadline = Math.max(
                1, Duration.between(Instant.now(), end).plus(DEADLINE_BACKSTOP).toSeconds());
        reviewJobs.create(reviewJobs.job(pod, deadline));
        sandboxes.put(name, new Sandbox(name, null));
        log.info("Sandbox Job created: {}", name);
        return new Handle(name);
    }

    @Override
    public Observation observe(Handle handle, Stage stage, Instant deadline) {
        Sandbox sandbox = sandbox(handle);
        String pod = pod(sandbox, deadline);
        return switch (stage) {
            case INITIALIZED -> {
                KubernetesPods.Waited waited = pods.waitFor(pod, KubernetesPods::initializationEnded, until(deadline));
                if (waited.timedOut()) {
                    stop(sandbox, pod, Container.INITIALIZER);
                    yield new Observation(true, null);
                }
                Pod ended = undisrupted(present(waited.pod()));
                if (KubernetesPods.notIsolated(ended)) {
                    throw new SandboxInfrastructureException(
                            "The network policy did not filter the sandbox Pod in time");
                }
                String waiting = KubernetesPods.fatalWaiting(ended);
                if (waiting != null) {
                    log.warn("Sandbox Pod {} could not start a container: {}", pod, waiting);
                }
                yield new Observation(false, KubernetesPods.exitCode(ended, KubernetesPods.INITIALIZER));
            }
            case STARTED -> {
                KubernetesPods.Waited waited = pods.waitFor(
                        pod,
                        candidate -> KubernetesPods.running(candidate, KubernetesPods.LOOP)
                                || KubernetesPods.runtimeEnded(candidate),
                        until(deadline));
                Pod seen = waited.pod();
                if (waited.timedOut() || seen == null) {
                    yield new Observation(true, null);
                }
                yield KubernetesPods.running(seen, KubernetesPods.LOOP)
                        ? new Observation(false, null)
                        : new Observation(false, exitOrKilled(undisrupted(seen)));
            }
            case ENDED -> {
                KubernetesPods.Waited waited = pods.waitFor(pod, KubernetesPods::runtimeEnded, until(deadline));
                if (waited.timedOut()) {
                    stop(sandbox, pod, Container.RUNTIME);
                    yield new Observation(true, null);
                }
                Pod ended = undisrupted(present(waited.pod()));
                Integer exit = KubernetesPods.exitCode(ended, KubernetesPods.LOOP);
                if (exit == null && ended.getMetadata().getDeletionTimestamp() != null) {
                    // A kill deletes the Job with its Pod, which stops the runtime as a deadline does: its exit, and
                    // the result it uploads in the grace period, come after the deletion starts.
                    Pod stopped = pods.awaitRuntimeExit(pod);
                    exit = stopped == null ? null : KubernetesPods.exitCode(stopped, KubernetesPods.LOOP);
                    if (exit == null) {
                        exit = SandboxLayout.EXIT_KILLED;
                    }
                }
                yield new Observation(false, exit);
            }
        };
    }

    /**
     * The Pod, unless Kubernetes ended it: an evicted or preempted Pod is the cluster's failure, which the ledger tries
     * again. The Job does not ({@code backoffLimit: 0}), and no {@code podFailurePolicy} of {@code Ignore} runs a second
     * attempt inside one Job.
     */
    private static Pod undisrupted(Pod pod) {
        String reason = KubernetesPods.disruption(pod);
        if (reason != null) {
            throw new SandboxInfrastructureException("Kubernetes ended the sandbox Pod: " + reason);
        }
        return pod;
    }

    /** The sandbox's Pod; a review's Job creates it, so the first observation waits for it, at most two minutes. */
    private String pod(Sandbox sandbox, Instant deadline) {
        String known = sandbox.pod;
        if (known != null) {
            return known;
        }
        Duration wait = until(deadline);
        if (wait.compareTo(KubernetesSandboxJobs.CHILD_START) > 0) {
            wait = KubernetesSandboxJobs.CHILD_START;
        }
        String name = reviewJobs
                .awaitPod(sandbox.name, wait, () -> sandbox.killed)
                .getMetadata()
                .getName();
        sandbox.pod = name;
        return name;
    }

    @Override
    public boolean running(Handle handle) {
        String pod = sandbox(handle).pod;
        if (pod == null) {
            return false;
        }
        // Lease heartbeats must use the watch state instead of waiting on an API-server request.
        try {
            Pod current = cache.pod(pod);
            return current != null
                    && current.getMetadata().getDeletionTimestamp() == null
                    && KubernetesPods.running(current, KubernetesPods.LOOP);
        } catch (KubernetesClientException unavailable) {
            // An unknown loop state keeps worker renewal active and must not roll back its heartbeat.
            return false;
        }
    }

    /** The container's log, or the one kept when a stage's deadline stopped the sandbox. */
    @Override
    public String transcript(Handle handle, Container container) {
        Sandbox sandbox = sandbox(handle);
        String kept = sandbox.kept.get(container);
        if (kept != null) {
            return kept;
        }
        String pod = sandbox.pod;
        return pod == null ? "" : logs(pod, container);
    }

    /**
     * Records the exit, then deletes the Job with its Pod ({@code Foreground}). The Pod's log goes with it: the runner
     * reads no transcript after a kill. A review whose Job has created no Pod yet then creates none.
     */
    @Override
    public void kill(Handle handle) {
        if (!handle.id().startsWith(REVIEW_PREFIX)) {
            return;
        }
        Sandbox sandbox = sandboxes.get(handle.id());
        if (sandbox != null) {
            sandbox.killed = true;
        }
        String pod = pod(handle, sandbox);
        if (pod != null && first(sandbox)) {
            exits.recordStop(SandboxExits.Kind.REVIEW, pod);
        }
        reviewJobs.deleteWithPod(handle.id());
    }

    /**
     * Stops the sandbox at a stage's deadline. The worker deletes the Job, never only its Pod, and the Pod goes before
     * its Job ({@code Foreground}, ADR 0068 § The Heph loop). The worker's Role grants no Pod patch or delete. The
     * deletion takes the Pod's log and exit code with it, so the stage's transcript and the exit are read first.
     */
    private void stop(Sandbox sandbox, String pod, Container container) {
        sandbox.kept.put(container, logs(pod, container));
        if (first(sandbox)) {
            exits.recordStop(SandboxExits.Kind.REVIEW, pod);
        }
        reviewJobs.deleteWithPod(sandbox.name);
    }

    /** Whether this call records the exit: a sandbox of another process, unknown here, records it each time. */
    private static boolean first(@Nullable Sandbox sandbox) {
        return sandbox == null || sandbox.recorded.compareAndSet(false, true);
    }

    private String logs(String pod, Container container) {
        return pods.logs(pod, container == Container.INITIALIZER ? KubernetesPods.INITIALIZER : KubernetesPods.LOOP);
    }

    /** Records the exit, then deletes the Job with its Pod ({@code Foreground}), as {@link #kill} does. */
    @Override
    public void remove(Handle handle) {
        if (!handle.id().startsWith(REVIEW_PREFIX)) {
            return;
        }
        Sandbox sandbox = sandboxes.remove(handle.id());
        SandboxException failure = null;
        String pod = null;
        try {
            pod = pod(handle, sandbox);
        } catch (SandboxException e) {
            failure = e;
        }
        // Before the delete: the Pod's exit code and log go with it.
        if (pod != null && first(sandbox)) exits.record(SandboxExits.Kind.REVIEW, pod);
        try {
            reviewJobs.deleteWithPod(handle.id());
        } catch (SandboxException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The Pod that the review's Job created; null before the Job created it. */
    private @Nullable String pod(Handle handle, @Nullable Sandbox sandbox) {
        String known = sandbox == null ? null : sandbox.pod;
        if (known != null) {
            return known;
        }
        Pod created = reviewJobs.podOf(handle.id());
        return created == null ? null : created.getMetadata().getName();
    }

    /** The review Jobs in the sandbox namespace, whichever worker created them. */
    @Override
    public List<Listed> list() {
        List<Listed> listed = new ArrayList<>();
        for (Job job : reviewJobs.list()) {
            // A Job that is being deleted waits for its Pod; it is already removed, and its exit already recorded.
            if (job.getMetadata().getDeletionTimestamp() != null) {
                continue;
            }
            Map<String, String> labels = labels(job);
            Optional<UUID> id = uuid(labels.get(KubernetesWorker.JOB_ID));
            if (id.isEmpty()) {
                continue;
            }
            listed.add(new Listed(
                    new Handle(job.getMetadata().getName()),
                    Kind.REVIEW,
                    id.get(),
                    attempt(labels.get(KubernetesWorker.JOB_ATTEMPT)),
                    created(job)));
        }
        return listed;
    }

    private static Map<String, String> labels(HasMetadata object) {
        Map<String, String> labels = object.getMetadata().getLabels();
        return labels == null ? Map.of() : labels;
    }

    private static Optional<UUID> uuid(@Nullable String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static @Nullable Integer attempt(@Nullable String value) {
        return value != null && value.matches("[0-9]{1,9}") ? Integer.valueOf(value) : null;
    }

    private static @Nullable Instant created(HasMetadata object) {
        String created = object.getMetadata().getCreationTimestamp();
        try {
            return created == null ? null : Instant.parse(created);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * One Job name per attempt: the API refuses a second Job of an attempt that already has one. The Job controller
     * labels each Pod with this name, so it stays within the 63 characters of a label value.
     */
    static String jobName(UUID jobId, @Nullable Integer attempt) {
        String suffix = attempt == null ? "x" + UUID.randomUUID().toString().substring(0, 7) : attempt.toString();
        return REVIEW_PREFIX + jobId + "-" + suffix;
    }

    private Sandbox sandbox(Handle handle) {
        Sandbox sandbox = sandboxes.get(handle.id());
        if (sandbox == null) {
            throw new SandboxException("The sandbox is gone");
        }
        return sandbox;
    }

    private static Pod present(@Nullable Pod pod) {
        if (pod == null) {
            throw new SandboxException("The sandbox Pod was deleted");
        }
        return pod;
    }

    /** A runtime that ended: its exit, or SIGKILL's when a Pod failure left none. */
    private static int exitOrKilled(Pod pod) {
        Integer exit = KubernetesPods.exitCode(pod, KubernetesPods.LOOP);
        return exit == null ? SandboxLayout.EXIT_KILLED : exit;
    }

    private static Duration until(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
