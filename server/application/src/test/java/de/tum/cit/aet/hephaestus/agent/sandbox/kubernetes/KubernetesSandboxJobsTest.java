package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import io.fabric8.kubernetes.api.model.DeleteOptions;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Review attempts as {@code batch/v1} Jobs against the client's CRUD mock API server. The mock runs no Job controller,
 * no TTL controller and no garbage collector: a test creates the Pod that the controller would, the requests show what
 * the client asks of the API server, and the live Kubernetes test proves what the controllers do.
 */
@Tag("unit")
@EnableKubernetesMockClient(crud = true)
class KubernetesSandboxJobsTest {

    private static final String SANDBOXES = "hephaestus-sandboxes";
    private static final ResourceLimits LIMITS = new ResourceLimits(1.0, Duration.ofMinutes(5));

    private @Nullable KubernetesMockServer server;
    private @Nullable KubernetesClient client;
    private KubernetesWorker worker;
    private KubernetesPods pods;
    private KubernetesSandboxJobs jobs;

    @BeforeEach
    void setUp() {
        worker = KubernetesFixtures.worker(SANDBOXES);
        pods = new KubernetesPods(api(), worker, 10, ImagePullPolicy.IF_NOT_PRESENT);
        jobs = new KubernetesSandboxJobs(api(), worker);
    }

    @Test
    void shouldRunTheReviewPodOnceAsTheTemplateOfABoundedJobWhenTheJobIsBuilt() {
        Pod template = template(reviewName());

        Job job = jobs.job(template, 900L);

        assertThat(job.getMetadata().getName()).isEqualTo(template.getMetadata().getName());
        assertThat(job.getMetadata().getLabels())
                .isEqualTo(template.getMetadata().getLabels());
        assertThat(job.getSpec().getCompletions()).isEqualTo(1);
        assertThat(job.getSpec().getParallelism()).isEqualTo(1);
        assertThat(job.getSpec().getBackoffLimit())
                .as("PostgreSQL decides a retry")
                .isZero();
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(900L);
        assertThat(job.getSpec().getTtlSecondsAfterFinished()).isEqualTo(900);
        assertThat(job.getSpec().getTemplate().getSpec()).isEqualTo(template.getSpec());
        assertThat(job.getSpec().getTemplate().getMetadata().getLabels())
                .isEqualTo(template.getMetadata().getLabels());
    }

    @Test
    void shouldRefuseASecondJobOfTheNameWhenCreated() {
        Job job = jobs.job(template(reviewName()), 900L);
        jobs.create(job);

        assertThatThrownBy(() -> jobs.create(job))
                .isExactlyInstanceOf(SandboxException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void shouldReturnThePodOfTheJobWhenItAppears() {
        String name = reviewName();
        jobs.create(jobs.job(template(name), 900L));
        api().pods().inNamespace(SANDBOXES).resource(child(name, "other-job")).create();
        api().pods().inNamespace(SANDBOXES).resource(child(name, name)).create();

        Pod found = jobs.awaitPod(name, Duration.ofSeconds(10), () -> false);

        assertThat(found.getMetadata().getLabels()).containsEntry(KubernetesSandboxJobs.JOB_NAME, name);
        assertThat(found.getMetadata().getName()).isEqualTo(name + "-" + name);
    }

    @Test
    void shouldEndAsAnInfrastructureFailureWhenNoPodOfTheJobAppears() {
        String name = reviewName();
        jobs.create(jobs.job(template(name), 900L));

        assertThatThrownBy(() -> jobs.awaitPod(name, Duration.ofMillis(500), () -> false))
                .isExactlyInstanceOf(SandboxInfrastructureException.class);
    }

    @Test
    void shouldEndTheWaitAsNoRoomWhenTheSandboxQuotaRefusedThePodOfTheJob() {
        String name = reviewName();
        jobs.create(jobs.job(template(name), 900L));
        refusedByTheQuota(name);

        assertThatThrownBy(() -> jobs.awaitPod(name, Duration.ofSeconds(10), () -> false))
                .isInstanceOfSatisfying(
                        SandboxCapacityException.class,
                        refused ->
                                assertThat(refused.reason()).isEqualTo(SandboxCapacityException.Reason.QUOTA_EXCEEDED));
    }

    @Test
    void shouldTakeThePodOfTheJobOverAnEarlierRefusalOfIt() {
        String name = reviewName();
        jobs.create(jobs.job(template(name), 900L));
        refusedByTheQuota(name);
        api().pods().inNamespace(SANDBOXES).resource(child(name, name)).create();

        assertThatCode(() -> jobs.requireRoom(name))
                .as("room freed and the Job controller created the Pod: the refusal is history")
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotTakeTheRefusalOfAnEarlierJobOfTheSameNameForThisJob() {
        String name = reviewName();
        jobs.create(jobs.job(template(name), 900L));
        refusedByTheQuota(name, "uid-of-an-earlier-job");

        assertThatThrownBy(() -> jobs.awaitPod(name, Duration.ofSeconds(3), () -> false))
                .isExactlyInstanceOf(SandboxInfrastructureException.class);
    }

    /** The event that the Job controller records when the quota refuses the Pod of the Job of the name. */
    private void refusedByTheQuota(String name) {
        refusedByTheQuota(
                name,
                api().batch()
                        .v1()
                        .jobs()
                        .inNamespace(SANDBOXES)
                        .withName(name)
                        .get()
                        .getMetadata()
                        .getUid());
    }

    private void refusedByTheQuota(String name, String uid) {
        api().v1()
                .events()
                .inNamespace(SANDBOXES)
                .resource(new EventBuilder()
                        .withNewMetadata()
                        .withName(name + ".refused")
                        .endMetadata()
                        .withNewInvolvedObject()
                        .withKind("Job")
                        .withName(name)
                        .withNamespace(SANDBOXES)
                        .withUid(uid)
                        .endInvolvedObject()
                        .withType("Warning")
                        .withReason("FailedCreate")
                        .withMessage("Error creating: pods \"" + name + "-x\" is forbidden: exceeded quota:"
                                + " hephaestus-sandbox-quota, requested: requests.memory=1Gi,"
                                + " used: requests.memory=8Gi, limited: requests.memory=8Gi")
                        .build())
                .create();
    }

    @Test
    void shouldEndTheWaitAsCancelledWhenTheAttemptIsCancelledBeforeItsPodAppears() {
        String name = reviewName();
        jobs.create(jobs.job(template(name), 900L));

        assertThatThrownBy(() -> jobs.awaitPod(name, Duration.ofSeconds(30), () -> true))
                .isInstanceOf(SandboxCancelledException.class);
    }

    @Test
    void shouldDeleteTheJobWithItsPodInTheBackgroundAndTakeAGoneJobAsDoneWhenDeleted() throws InterruptedException {
        String name = reviewName();
        jobs.create(jobs.job(template(name), 900L));
        KubernetesMockServer api = Objects.requireNonNull(server);
        while (api.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // Drop the requests of the setup.
        }

        jobs.delete(name);

        RecordedRequest deletion = Objects.requireNonNull(api.takeRequest(5, TimeUnit.SECONDS));
        assertThat(deletion.getMethod()).isEqualTo("DELETE");
        DeleteOptions options =
                api().getKubernetesSerialization().unmarshal(deletion.getUtf8Body(), DeleteOptions.class);
        assertThat(options.getPropagationPolicy())
                .as("the API's default for a Job would orphan its Pod")
                .isEqualTo("Background");
        assertThat(api().batch()
                        .v1()
                        .jobs()
                        .inNamespace(SANDBOXES)
                        .withName(name)
                        .get())
                .isNull();
        jobs.delete(name);
    }

    @Test
    void shouldListOnlyReviewJobsWhenListed() {
        String review = reviewName();
        jobs.create(jobs.job(template(review), 900L));
        Pod host = template("hephaestus-host-" + UUID.randomUUID());
        host.getMetadata().setLabels(worker.labels(KubernetesWorker.KIND_HOST));
        jobs.create(jobs.job(host, 900L));

        assertThat(jobs.list()).extracting(job -> job.getMetadata().getName()).containsExactly(review);
    }

    private KubernetesClient api() {
        return Objects.requireNonNull(client, "the mock server injects the client");
    }

    private static String reviewName() {
        return "hephaestus-review-" + UUID.randomUUID() + "-1";
    }

    private Pod template(String name) {
        return pods.workspacePod(
                new KubernetesPods.Workload(name, worker.labels(KubernetesWorker.KIND_REVIEW), "agent", "agent"),
                Map.of(),
                List.of("node", "run.ts"),
                LIMITS,
                KubernetesPods.REVIEW);
    }

    /** The Pod that the Job controller creates for the Job of the name. */
    private Pod child(String name, String job) {
        return new PodBuilder()
                .withNewMetadata()
                .withName(name + "-" + job)
                .withNamespace(SANDBOXES)
                .addToLabels(worker.labels(KubernetesWorker.KIND_REVIEW))
                .addToLabels(KubernetesSandboxJobs.JOB_NAME, job)
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(KubernetesPods.RUNTIME)
                .withImage("agent")
                .endContainer()
                .endSpec()
                .build();
    }
}
