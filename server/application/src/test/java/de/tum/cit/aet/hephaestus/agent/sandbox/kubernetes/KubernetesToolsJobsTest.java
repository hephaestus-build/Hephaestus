package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsJobs;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the tools Jobs ask of the API server: one Job per attempt identity, the worker's own tools program, and no Job
 * of another identity left beside it. The live Kubernetes test proves what the controllers do.
 */
@Tag("unit")
@EnableKubernetesMockClient(crud = true)
class KubernetesToolsJobsTest {

    private static final String SANDBOXES = "hephaestus-sandboxes";
    private static final long WORKSPACE = 41L;
    private static final UUID TURN = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CONTEXT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private @Nullable KubernetesClient client;
    private KubernetesToolsJobs tools;

    @BeforeEach
    void setUp() {
        KubernetesWorker worker = KubernetesFixtures.worker(SANDBOXES);
        worker.proved(new KubernetesIsolationProbe.Proof(Instant.EPOCH, List.of(), true, "", 248));
        KubernetesClient api = api();
        tools = new KubernetesToolsJobs(
                api,
                new KubernetesPods(api, worker, 10, ImagePullPolicy.IF_NOT_PRESENT),
                new KubernetesSandboxJobs(api, worker),
                worker,
                "ghcr.io/hephaestus-build/agent:test");
    }

    @Test
    void shouldReplaceEveryOtherJobOfTheTurnAndServeItsOwnNonceWhenItsPodIsReady() throws Exception {
        CompletableFuture<ToolsJobs.Endpoint> earlier = acquireAsync(UUID.randomUUID(), CONTEXT);
        String earlierJob = podReady(1, "10.0.0.6");
        earlier.get(30, TimeUnit.SECONDS);

        CompletableFuture<ToolsJobs.Endpoint> acquired = acquireAsync(LEASE, CONTEXT);
        String job = podReady(1, "10.0.0.7");
        ToolsJobs.Endpoint endpoint = acquired.get(30, TimeUnit.SECONDS);

        assertThat(job).isNotEqualTo(earlierJob);
        assertThat(jobs()).extracting(found -> found.getMetadata().getName()).containsExactly(job);
        assertThat(endpoint.host()).isEqualTo("10.0.0.7");
        assertThat(endpoint.port()).isEqualTo(KubernetesToolsJobs.PORT);
        assertThat(endpoint.nonce()).isEqualTo(env(jobs().getFirst(), "TOOLS_NONCE"));
        Map<String, String> labels = jobs().getFirst().getMetadata().getLabels();
        assertThat(labels)
                .containsEntry(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TOOLS)
                .containsEntry(KubernetesWorker.TURN_ID, TURN.toString())
                .containsEntry(KubernetesWorker.LEASE_ID, LEASE.toString())
                .containsEntry(KubernetesWorker.CONTEXT_GENERATION, CONTEXT.toString());
        ConfigMap program =
                api().configMaps().inNamespace(SANDBOXES).list().getItems().getFirst();
        assertThat(program.getImmutable()).isTrue();
        assertThat(program.getData()).containsOnlyKeys("tools-server.ts", "tool-protocol.ts");
        assertThat(jobs().getFirst().getSpec().getTemplate().getSpec().getVolumes())
                .filteredOn(volume -> volume.getConfigMap() != null)
                .singleElement()
                .satisfies(volume -> assertThat(volume.getConfigMap().getName())
                        .isEqualTo(program.getMetadata().getName()));
    }

    @Test
    void shouldShareTheJobAndItsNonceWhenTheSameIdentityIsAcquiredAgain() throws Exception {
        CompletableFuture<ToolsJobs.Endpoint> first = acquireAsync(LEASE, CONTEXT);
        podReady(1, "10.0.0.8");
        ToolsJobs.Endpoint again = acquireAsync(LEASE, CONTEXT).get(30, TimeUnit.SECONDS);

        assertThat(again).isEqualTo(first.get(30, TimeUnit.SECONDS));
        assertThat(jobs()).hasSize(1);
    }

    @Test
    void shouldDeleteOnlyItsOwnJobWhenAnOldContextIsDiscardedAndAgainWhenItIsDiscardedTwice() throws Exception {
        UUID replacement = UUID.randomUUID();
        CompletableFuture<ToolsJobs.Endpoint> current = acquireAsync(LEASE, replacement);
        String job = podReady(1, "10.0.0.9");
        current.get(30, TimeUnit.SECONDS);

        tools.discard(WORKSPACE, TURN, 1, LEASE, CONTEXT);
        tools.discard(WORKSPACE, TURN, 1, LEASE, CONTEXT);

        assertThat(jobs())
                .as("a delayed discard of the old context leaves the replacement")
                .extracting(found -> found.getMetadata().getName())
                .containsExactly(job);
        tools.discard(WORKSPACE, TURN, 1, LEASE, replacement);
        assertThat(jobs()).isEmpty();
    }

    @Test
    void shouldDeleteTheJobAndRefuseWhenItsIdentityIsDiscardedWhileItStarts() {
        CompletableFuture<ToolsJobs.Endpoint> starting = acquireAsync(LEASE, CONTEXT);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> jobs().size() == 1);

        tools.discard(WORKSPACE, TURN, 1, LEASE, CONTEXT);

        assertThatThrownBy(() -> starting.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(SandboxCancelledException.class);
        assertThat(jobs()).isEmpty();
        assertThatThrownBy(() -> tools.acquire(
                        WORKSPACE, TURN, 1, LEASE, CONTEXT, Instant.now().plusSeconds(60)))
                .as("a discarded identity is never acquired again, also when the discard came first")
                .isInstanceOf(SandboxCancelledException.class);
        assertThat(jobs()).isEmpty();
    }

    @Test
    void shouldStartNoJobOnceTheWorkerDrainsAndEndTheOneThatStartsMeanwhile() {
        CompletableFuture<ToolsJobs.Endpoint> starting = acquireAsync(LEASE, CONTEXT);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> jobs().size() == 1);

        tools.close();

        assertThatThrownBy(() -> starting.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(SandboxCancelledException.class);
        assertThat(jobs()).isEmpty();
        assertThatThrownBy(() -> tools.acquire(
                        WORKSPACE,
                        TURN,
                        1,
                        UUID.randomUUID(),
                        CONTEXT,
                        Instant.now().plusSeconds(60)))
                .isInstanceOf(SandboxCancelledException.class);
        assertThat(jobs()).isEmpty();
    }

    @Test
    void shouldDeleteTheJobAndRefuseAsBusyWhenTheQuotaRefusesItsPod() {
        CompletableFuture<ToolsJobs.Endpoint> starting = acquireAsync(LEASE, CONTEXT);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> jobs().size() == 1);
        Job job = jobs().getFirst();

        // The Job controller's report: the conversations quota had no room for the Pod.
        api().v1()
                .events()
                .inNamespace(SANDBOXES)
                .resource(new EventBuilder()
                        .withNewMetadata()
                        .withName(job.getMetadata().getName() + ".refused")
                        .endMetadata()
                        .withNewInvolvedObject()
                        .withKind("Job")
                        .withName(job.getMetadata().getName())
                        .withNamespace(SANDBOXES)
                        .withUid(job.getMetadata().getUid())
                        .endInvolvedObject()
                        .withType("Warning")
                        .withReason("FailedCreate")
                        .withMessage(
                                "Error creating: pods \"" + job.getMetadata().getName()
                                        + "-x\" is forbidden: exceeded quota: hephaestus-conversations")
                        .build())
                .create();

        assertThatThrownBy(() -> starting.get(30, TimeUnit.SECONDS))
                .as("well before the turn's deadline of one minute")
                .hasCauseInstanceOf(SandboxCapacityException.class);
        assertThat(jobs()).isEmpty();
    }

    @Test
    void shouldKeepOnlyTheLiveBindingsJobsAndRefuseTheRemovedIdentitiesAfterwards() throws Exception {
        CompletableFuture<ToolsJobs.Endpoint> live = acquireAsync(LEASE, CONTEXT);
        String kept = podReady(1, "10.0.0.10");
        live.get(30, TimeUnit.SECONDS);
        // A Job that another worker started for a turn that has ended since.
        UUID otherTurn = UUID.randomUUID();
        CompletableFuture<ToolsJobs.Endpoint> ended = CompletableFuture.supplyAsync(() -> tools.acquire(
                WORKSPACE, otherTurn, 1, LEASE, CONTEXT, Instant.now().plus(Duration.ofMinutes(1))));
        podReady(2, "10.0.0.11");
        ended.get(30, TimeUnit.SECONDS);

        tools.retainOnly(List.of(new ToolsJobs.Binding(WORKSPACE, TURN, 1, LEASE, CONTEXT)));
        tools.retainOnly(List.of(new ToolsJobs.Binding(WORKSPACE, TURN, 1, LEASE, CONTEXT)));

        assertThat(jobs()).extracting(job -> job.getMetadata().getName()).containsExactly(kept);
        assertThatThrownBy(() -> tools.acquire(
                        WORKSPACE, otherTurn, 1, LEASE, CONTEXT, Instant.now().plusSeconds(60)))
                .isInstanceOf(SandboxCancelledException.class);
    }

    @Test
    void shouldEndAnAcquireWhenAnotherWorkerRemovesItsJob() {
        KubernetesWorker other = KubernetesFixtures.worker(SANDBOXES);
        KubernetesClient api = api();
        var otherWorker = new KubernetesToolsJobs(
                api,
                new KubernetesPods(api, other, 10, ImagePullPolicy.IF_NOT_PRESENT),
                new KubernetesSandboxJobs(api, other),
                other,
                "ghcr.io/hephaestus-build/agent:test");
        CompletableFuture<ToolsJobs.Endpoint> starting = acquireAsync(LEASE, CONTEXT);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> jobs().size() == 1);

        otherWorker.retainOnly(List.of());

        assertThatThrownBy(() -> starting.get(30, TimeUnit.SECONDS))
                .as("long before the turn's deadline")
                .hasCauseInstanceOf(SandboxCancelledException.class);
        assertThat(jobs()).isEmpty();
    }

    private CompletableFuture<ToolsJobs.Endpoint> acquireAsync(UUID lease, UUID context) {
        return CompletableFuture.supplyAsync(() ->
                tools.acquire(WORKSPACE, TURN, 1, lease, context, Instant.now().plus(Duration.ofMinutes(1))));
    }

    /** Plays the Job controller and the kubelet: once a new Job exists, its Pod runs and is ready at the address. */
    private String podReady(int expectedJobs, String address) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> jobs().size() == expectedJobs
                        && jobs().stream().anyMatch(job -> pod(job.getMetadata().getName()) == null));
        Job job = jobs().stream()
                .filter(found -> pod(found.getMetadata().getName()) == null)
                .findFirst()
                .orElseThrow();
        String name = job.getMetadata().getName();
        api().pods()
                .inNamespace(SANDBOXES)
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName(name + "-pod")
                        .withNamespace(SANDBOXES)
                        .withLabels(Map.of(KubernetesSandboxJobs.JOB_NAME, name))
                        .endMetadata()
                        .withNewStatus()
                        .withPodIP(address)
                        .addNewCondition()
                        .withType("Ready")
                        .withStatus("True")
                        .endCondition()
                        .endStatus()
                        .build())
                .create();
        return name;
    }

    private @Nullable Pod pod(String job) {
        return api()
                .pods()
                .inNamespace(SANDBOXES)
                .withLabel(KubernetesSandboxJobs.JOB_NAME, job)
                .list()
                .getItems()
                .stream()
                .findFirst()
                .orElse(null);
    }

    private List<Job> jobs() {
        return api().batch().v1().jobs().inNamespace(SANDBOXES).list().getItems();
    }

    private static String env(Job job, String name) {
        return job.getSpec().getTemplate().getSpec().getContainers().getFirst().getEnv().stream()
                .filter(variable -> variable.getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private KubernetesClient api() {
        return Objects.requireNonNull(client);
    }
}
