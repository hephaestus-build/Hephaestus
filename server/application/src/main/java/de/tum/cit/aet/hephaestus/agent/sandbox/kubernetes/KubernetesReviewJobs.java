package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
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
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Practice-review attempts as native {@code batch/v1} Jobs: one Job per claimed attempt, named after the job and the
 * attempt, whose Pod template is the review Pod of {@link KubernetesPods#workspacePod}. The Job runs one Pod and
 * replaces none. When its worker no longer bounds it, the control plane does: its deadline stops it, and the TTL
 * controller deletes the finished Job with its Pod.
 *
 * <p>A Job is a bounded lifecycle only. PostgreSQL keeps every claim, retry, attempt, admission and delivery; the Job
 * controller's own retries are off. The gateway session of the attempt stays the authority over what a result belongs
 * to.
 */
public class KubernetesReviewJobs {

    /** The label the Job controller gives each Pod of a Job: the Job's name. */
    static final String JOB_NAME = "batch.kubernetes.io/job-name";

    /** How long the TTL controller keeps a finished Job, with its Pod, that its worker did not delete. */
    static final Duration TTL_AFTER_FINISHED = Duration.ofMinutes(15);

    /** How long the Job controller may take to create the Pod of a new Job. Scheduling and the image come after. */
    static final Duration CHILD_START = Duration.ofMinutes(2);

    /** How often a wait for the Pod of a Job looks at the attempt's cancellation. */
    private static final Duration CANCEL_POLL = Duration.ofMillis(200);

    private final KubernetesClient client;
    private final KubernetesWorker worker;

    public KubernetesReviewJobs(KubernetesClient client, KubernetesWorker worker) {
        this.client = client;
        this.worker = worker;
    }

    /**
     * The Job of a review Pod: the Pod's name and labels, and the Pod as its template.
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
                // No replacement while a failed Pod still terminates.
                .withPodReplacementPolicy("Failed")
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
                throw new SandboxException("A review Job of this name already exists", e);
            }
            throw KubernetesPods.failure("Could not create a review Job", e);
        }
    }

    /**
     * Waits until the Job controller has created the Job's Pod, and returns it.
     *
     * @param cancelled read at least every {@link #CANCEL_POLL}, so a cancellation ends the wait promptly
     * @throws SandboxCancelledException when the attempt was cancelled first
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
            throw KubernetesPods.failure("Could not watch the Pods of a review Job", e);
        }
        try {
            while (!cancelled.getAsBoolean()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new SandboxInfrastructureException("The review Job created no Pod within " + timeout);
                }
                try {
                    return appeared.get(Math.min(left, CANCEL_POLL.toNanos()), TimeUnit.NANOSECONDS)
                            .getFirst();
                } catch (TimeoutException e) {
                    // Not yet: look at the cancellation and the deadline again.
                }
            }
            throw new SandboxCancelledException("Job cancelled while its review Pod was created");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxInfrastructureException("Interrupted while a review Job created its Pod", e);
        } catch (ExecutionException e) {
            throw watchFailure(e);
        } finally {
            appeared.cancel(true);
        }
    }

    private static SandboxException watchFailure(ExecutionException e) {
        if (e.getCause() instanceof KubernetesClientException api) {
            return KubernetesPods.failure("Could not watch the Pods of a review Job", api);
        }
        return new SandboxInfrastructureException("Could not watch the Pods of a review Job", e);
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
            throw KubernetesPods.failure("Could not read the Pod of a review Job", e);
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
            throw KubernetesPods.failure("Could not delete a review Job", e);
        }
    }
}
