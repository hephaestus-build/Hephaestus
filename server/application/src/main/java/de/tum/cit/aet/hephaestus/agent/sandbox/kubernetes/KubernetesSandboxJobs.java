package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandbox Pods as native {@code batch/v1} Jobs: one Job per practice-review attempt, named after the job and the
 * attempt, whose Pod template is the review Pod of {@link KubernetesPods#workspacePod}, and one Job per isolation probe
 * Pod. The Job runs one Pod and replaces none. When its worker no longer bounds it, the control plane does: its deadline
 * stops it, also while its Pod waits for a node, which a Pod's own deadline does not count, and the TTL controller
 * deletes the finished Job with its Pod.
 *
 * <p>A Job is a bounded lifecycle only. PostgreSQL keeps every claim, retry, attempt, admission and delivery; the Job
 * controller's own retries are off. The gateway session of the attempt stays the authority over what a result belongs
 * to.
 */
public class KubernetesSandboxJobs {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxJobs.class);

    /** The label the Job controller gives each Pod of a Job: the Job's name. */
    static final String JOB_NAME = "batch.kubernetes.io/job-name";

    /** How long the TTL controller keeps a finished Job, with its Pod, that its worker did not delete. */
    static final Duration TTL_AFTER_FINISHED = Duration.ofMinutes(15);

    /** How long the Job controller may take to create the Pod of a new Job. Scheduling and the image come after. */
    static final Duration CHILD_START = Duration.ofMinutes(2);

    /** How often a wait for the Pod of a Job looks at the attempt's cancellation. */
    private static final Duration CANCEL_POLL = Duration.ofMillis(200);

    /** How often a wait for the Pod of a Job reads the Job's events for a refusal of the sandbox quota. */
    private static final Duration REFUSAL_POLL = Duration.ofSeconds(2);

    /** The quota admission's refusal, in the message of the Job controller's {@code FailedCreate} event. */
    private static final String QUOTA_REFUSAL = "exceeded quota";

    private final KubernetesClient client;
    private final KubernetesWorker worker;

    /** Whether the API refused this worker the events of the sandbox namespace: it stops asking. */
    private final AtomicBoolean eventsDenied = new AtomicBoolean();

    public KubernetesSandboxJobs(KubernetesClient client, KubernetesWorker worker) {
        this.client = client;
        this.worker = worker;
    }

    /**
     * The Job of a sandbox Pod: the Pod's name and labels, and the Pod as its template.
     *
     * @param activeDeadlineSeconds how long the Job may run, counted from its start
     */
    public Job job(Pod pod, long activeDeadlineSeconds) {
        return new JobBuilder()
                .withMetadata(pod.getMetadata())
                .withNewSpec()
                .withCompletions(1)
                .withParallelism(1)
                // The claim in PostgreSQL decides whether an attempt runs again, never the Job controller.
                .withBackoffLimit(0)
                .withActiveDeadlineSeconds(activeDeadlineSeconds)
                .withTtlSecondsAfterFinished((int) TTL_AFTER_FINISHED.toSeconds())
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(pod.getMetadata().getLabels())
                .endMetadata()
                .withSpec(pod.getSpec())
                .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * @throws SandboxException when a Job of the name exists: an attempt runs once, and an existing Job is never adopted
     * @throws SandboxInfrastructureException when the API fails
     */
    public void create(Job job) {
        try {
            client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(worker.namespace())
                    .resource(job)
                    .create();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                throw new SandboxException("A sandbox Job of this name already exists", e);
            }
            throw KubernetesPods.failure("Could not create a sandbox Job", e);
        }
    }

    /**
     * Waits until the Job controller has created the Job's Pod, and returns it.
     *
     * @param cancelled read at least every {@link #CANCEL_POLL}, so a cancellation ends the wait promptly
     * @throws SandboxCancelledException when the attempt was cancelled first
     * @throws SandboxCapacityException when the sandbox quota refused the Pod: the Job controller would keep trying
     * @throws SandboxInfrastructureException when no Pod of the Job appeared in time, or the Pods cannot be watched:
     *     neither says anything of the review
     */
    public Pod awaitPod(String job, Duration timeout, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<List<Pod>> appeared;
        try {
            appeared = client.pods()
                    .inNamespace(worker.namespace())
                    .withLabel(JOB_NAME, job)
                    .informOnCondition(pods -> !pods.isEmpty());
        } catch (KubernetesClientException e) {
            throw KubernetesPods.failure("Could not watch the Pods of a sandbox Job", e);
        }
        try {
            long nextRefusalCheck = System.nanoTime() + REFUSAL_POLL.toNanos();
            while (!cancelled.getAsBoolean()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new SandboxInfrastructureException("The sandbox Job created no Pod within " + timeout);
                }
                try {
                    return appeared.get(Math.min(left, CANCEL_POLL.toNanos()), TimeUnit.NANOSECONDS)
                            .getFirst();
                } catch (TimeoutException e) {
                    // Not yet: look at the quota, the cancellation and the deadline again.
                }
                if (System.nanoTime() - nextRefusalCheck >= 0) {
                    requireRoom(job);
                    nextRefusalCheck = System.nanoTime() + REFUSAL_POLL.toNanos();
                }
            }
            throw new SandboxCancelledException("Job cancelled while its sandbox Pod was created");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxInfrastructureException("Interrupted while a sandbox Job created its Pod", e);
        } catch (ExecutionException e) {
            throw watchFailure(e);
        } finally {
            appeared.cancel(true);
        }
    }

    /**
     * Throws when the Job controller reported that the sandbox quota refused this Job's Pod and no Pod of it exists: a
     * Pod that the controller created once room freed wins over its earlier refusal. The event names the Job by its
     * UID, so the event of an earlier Job of the same name does not count. Whether the quota can ever hold the Pod, the
     * caller asks the quota ({@code SandboxRoom#neverFits}). A worker that may not read the events stops
     * looking after one warning and leaves the wait to its timeout.
     */
    void requireRoom(String job) {
        if (eventsDenied.get()) {
            return;
        }
        try {
            Job read = client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(worker.namespace())
                    .withName(job)
                    .get();
            String uid = read == null ? null : read.getMetadata().getUid();
            if (uid == null) {
                return;
            }
            // The namespace holds only sandbox Jobs and Pods, so its events are few; the client matches the Job.
            boolean refused = client.v1().events().inNamespace(worker.namespace()).list().getItems().stream()
                    .anyMatch(event -> event.getInvolvedObject() != null
                            && uid.equals(event.getInvolvedObject().getUid())
                            && "FailedCreate".equals(event.getReason())
                            && event.getMessage() != null
                            && event.getMessage().contains(QUOTA_REFUSAL));
            if (refused && podOf(job) == null) {
                throw new SandboxCapacityException(
                        "The sandbox quota has no room for the Pod of a review Job",
                        SandboxCapacityException.Reason.QUOTA_EXCEEDED);
            }
        } catch (KubernetesClientException e) {
            if (e.getCode() == 403 && eventsDenied.compareAndSet(false, true)) {
                log.warn("The worker may not read the events of the sandbox namespace, so a review whose Pod the"
                        + " sandbox quota refuses waits for its timeout and spends a retry. Grant get and list on"
                        + " events there (docs/admin/kubernetes.mdx)");
            }
        } catch (SandboxException e) {
            throw e;
        } catch (RuntimeException e) {
            log.debug("Could not read whether the sandbox quota refused review Job {}", job, e);
        }
    }

    private static SandboxException watchFailure(ExecutionException e) {
        if (e.getCause() instanceof KubernetesClientException api) {
            return KubernetesPods.failure("Could not watch the Pods of a sandbox Job", api);
        }
        return new SandboxInfrastructureException("Could not watch the Pods of a sandbox Job", e);
    }

    /** The Pod that the Job created, or null before it created one. */
    public @Nullable Pod podOf(String job) {
        try {
            List<Pod> pods = client.pods()
                    .inNamespace(worker.namespace())
                    .withLabel(JOB_NAME, job)
                    .list()
                    .getItems();
            return pods.isEmpty() ? null : pods.getFirst();
        } catch (KubernetesClientException e) {
            throw KubernetesPods.failure("Could not read the Pod of a sandbox Job", e);
        }
    }

    /** The review Jobs. */
    public List<Job> list() {
        try {
            return client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(worker.namespace())
                    .withLabels(worker.selector(KubernetesWorker.KIND_REVIEW))
                    .list()
                    .getItems();
        } catch (KubernetesClientException e) {
            throw KubernetesPods.failure("Could not list review Jobs", e);
        }
    }

    /**
     * Deletes the Job and, in the background, its Pod; a Job that is already gone is no error. The propagation is
     * explicit: the API's own default for a {@code batch/v1} Job orphans its Pods.
     */
    public void delete(String job) {
        try {
            client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(worker.namespace())
                    .withName(job)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                    .delete();
        } catch (KubernetesClientException e) {
            throw KubernetesPods.failure("Could not delete a sandbox Job", e);
        }
    }
}
