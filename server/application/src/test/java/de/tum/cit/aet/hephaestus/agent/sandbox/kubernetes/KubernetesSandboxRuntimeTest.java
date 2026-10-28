package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.WorkerRegistryRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxAttemptRunner;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxReconciler;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import io.fabric8.mockwebserver.ServerRequest;
import io.fabric8.mockwebserver.ServerResponse;
import io.fabric8.mockwebserver.http.MockResponse;
import io.fabric8.mockwebserver.http.RecordedRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Kubernetes runtime against the client's CRUD mock API server: the Pods it writes, what it removes and keeps,
 * and how a review attempt ends. The mock runs no container, so what the Pods enforce on a node (the RuntimeClass,
 * seccomp, the read-only mounts, the PID namespace, the chart's NetworkPolicies) is proved on a live cluster only.
 */
@Tag("unit")
class KubernetesSandboxRuntimeTest {

    private static final String SANDBOXES = "hephaestus-sandboxes";
    private static final String IMAGE = "ghcr.io/example/agent@sha256:" + "a".repeat(64);
    private static final ResourceLimits LIMITS =
            new ResourceLimits(1024L * 1024 * 1024, 1.0, 256, Duration.ofMinutes(5));

    private @Nullable KubernetesMockServer server;
    private @Nullable KubernetesClient client;

    /**
     * The CRUD mock keeps no history of resource versions: a watch gets the objects that exist when it registers and
     * the events after that, not the events since the list that it follows. A Pod deleted between a wait's list and
     * its watch therefore never ends that wait. A test that deletes a Pod under a wait first waits for this latch,
     * which counts down once the mock has registered the watch of {@link #watchedPod}.
     */
    private final CountDownLatch watching = new CountDownLatch(1);

    private final AtomicReference<@Nullable String> watchedPod = new AtomicReference<>();

    private final ExecutorService background = Executors.newCachedThreadPool();
    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private static final String WORKER_ID = "worker-process-1";

    private String workerName;
    private KubernetesSandboxProperties properties;
    private KubernetesLiveWorkers liveWorkers;
    private KubernetesWorker worker;
    private KubernetesPods pods;
    private KubernetesSandboxJobs reviewJobs;

    private final List<KubernetesSandboxCache> caches = new ArrayList<>();

    /** A cache that has listed the namespace as it is now. */
    private KubernetesSandboxCache cache() {
        var cache = new KubernetesSandboxCache(api(), SANDBOXES);
        caches.add(cache);
        await().atMost(Duration.ofSeconds(10)).until(cache::current);
        return cache;
    }

    private KubernetesClient api() {
        return Objects.requireNonNull(client, "setUp creates the client");
    }

    @BeforeEach
    void setUp() {
        Map<ServerRequest, Queue<ServerResponse>> responses = new HashMap<>();
        var dispatcher = new KubernetesMixedDispatcher(responses) {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // The CRUD dispatcher registers a watch's listener before it returns the watch's response.
                MockResponse response = super.dispatch(request);
                String pod = watchedPod.get();
                String path = request.getPath();
                if (pod != null
                        && path.contains("watch=true")
                        && path.contains("/namespaces/" + SANDBOXES + "/pods")
                        && path.contains(pod)) {
                    watching.countDown();
                }
                return response;
            }
        };
        KubernetesMockServer started =
                new KubernetesMockServer(new Context(), new MockWebServer(), responses, dispatcher, true);
        server = started;
        started.init();
        client = started.createClient();

        workerName = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        properties = new KubernetesSandboxProperties(
                SANDBOXES,
                workerName,
                "fd00::7",
                "gvisor",
                "hephaestus/agent-seccomp-profile.json",
                "",
                "",
                "hephaestus-conversations",
                "hephaestus-reviews",
                Duration.ofMinutes(15));
        worker = new KubernetesWorker(properties, WORKER_ID, 8081);
        worker.proved(passed());
        WorkerRegistryRepository registry = mock(WorkerRegistryRepository.class);
        when(registry.findLiveWorkerIds(anyLong())).thenReturn(List.of(WORKER_ID));
        liveWorkers = new KubernetesLiveWorkers(registry);
        pods = new KubernetesPods(api(), worker, 10, ImagePullPolicy.IF_NOT_PRESENT);
        reviewJobs = new KubernetesSandboxJobs(api(), worker);
    }

    @AfterEach
    void tearDown() {
        background.shutdownNow();
        caches.forEach(KubernetesSandboxCache::close);
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            if (server != null) {
                server.destroy();
            }
        }
    }

    @Test
    void shouldBuildReviewPodWithRestrictedContainersAndReadOnlyRuntimeWorkspace() {
        Pod pod = pods.workspacePod(
                new KubernetesPods.Workload("review", Map.of(), IMAGE, "agent"),
                Map.of("B", "2", "A", "1"),
                List.of("node", "run.ts"),
                LIMITS,
                KubernetesPods.REVIEW);

        var spec = pod.getSpec();
        assertThat(spec.getRestartPolicy()).isEqualTo("Never");
        assertThat(spec.getAutomountServiceAccountToken()).isFalse();
        assertThat(spec.getEnableServiceLinks()).isFalse();
        // Unset is the API's false.
        assertThat(spec.getHostNetwork()).isNotEqualTo(true);
        assertThat(spec.getHostPID()).isNotEqualTo(true);
        assertThat(spec.getRuntimeClassName()).isEqualTo("gvisor");
        assertThat(spec.getActiveDeadlineSeconds())
                .as("the review's Job bounds it")
                .isNull();
        assertThat(spec.getDnsPolicy()).isEqualTo("None");
        assertThat(spec.getSecurityContext().getRunAsNonRoot()).isTrue();
        assertThat(spec.getSecurityContext().getSeccompProfile().getType()).isEqualTo("Localhost");
        assertThat(spec.getSecurityContext().getSeccompProfile().getLocalhostProfile())
                .isEqualTo("hephaestus/agent-seccomp-profile.json");

        assertThat(spec.getInitContainers())
                .extracting(Container::getName)
                .as("the isolation gate runs before the workspace is filled")
                .containsExactly(KubernetesPods.ISOLATION, KubernetesPods.INITIALIZER);
        Container initializer = spec.getInitContainers().get(1);
        Container runtime = spec.getContainers().getFirst();
        for (Container container : List.of(initializer, runtime)) {
            assertRestricted(container, KubernetesPods.SANDBOX_UID);
            assertSized(container, KubernetesPods.REVIEW);
            assertThat(container.getEnv()).extracting("name").containsExactly("A", "B");
            assertThat(container.getCommand()).startsWith(KubernetesPods.RLIMITS.toArray(String[]::new));
        }
        assertThat(initializer.getCommand()).endsWith(KubernetesPods.INITIALIZER_COMMAND.toArray(String[]::new));
        assertThat(runtime.getCommand()).containsExactly(limitedArgv("node", "run.ts"));
        assertThat(readOnly(initializer, "/workspace")).isFalse();
        assertThat(readOnly(runtime, "/workspace")).isTrue();
        assertThat(readOnly(runtime, "/workspace/out")).isFalse();
        assertThat(readOnly(runtime, "/tmp")).isFalse();
        assertThat(spec.getVolumes())
                .filteredOn(volume -> volume.getEmptyDir() != null
                        && "Memory".equals(volume.getEmptyDir().getMedium()))
                .as("only /run, which can hold a credential, is in memory")
                .singleElement()
                .satisfies(volume ->
                        assertThat(mountPath(runtime, volume.getName())).isEqualTo("/run"));
        assertThat(spec.getVolumes())
                .filteredOn(volume ->
                        volume.getEmptyDir() != null && volume.getEmptyDir().getMedium() == null)
                .extracting(volume -> mountPath(runtime, volume.getName()))
                .as("the attempt volumes and the scratch places are on disk")
                .containsExactlyInAnyOrder(
                        "/workspace",
                        "/workspace/work",
                        "/workspace/out",
                        "/workspace/.pi",
                        "/workspace/.sessions",
                        "/tmp",
                        "/home/agent/.local");
    }

    @Test
    void shouldBoundTheDiskOfEverySandboxPodSoThatNoneCanFillItsNode() {
        Map<String, String> review = Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_REVIEW);
        Map<String, String> host = Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_HOST);
        Pod reviewPod = pods.workspacePod(
                new KubernetesPods.Workload("r", review, IMAGE, "agent"),
                Map.of(),
                List.of("node"),
                LIMITS,
                KubernetesPods.REVIEW);
        Pod hostPod = pods.workspacePod(
                new KubernetesPods.Workload("h", host, IMAGE, "agent"),
                Map.of(),
                List.of("node"),
                LIMITS,
                KubernetesPods.HOST);
        Pod turnPod = pods.turnPod(new KubernetesPods.Workload("t", Map.of(), IMAGE, "sandbox"), LIMITS);

        assertDiskBounded(reviewPod, KubernetesPods.RUNTIME, KubernetesPods.REVIEW);
        assertDiskBounded(hostPod, KubernetesPods.RUNTIME, KubernetesPods.HOST);
        assertDiskBounded(turnPod, KubernetesPods.SANDBOX, KubernetesPods.TURN);
        Pod probePod = pods.probePod(
                new KubernetesPods.Workload("p", review, IMAGE, "agent"), List.of("true"), LIMITS, 60, true);
        for (Container each : Stream.concat(
                        probePod.getSpec().getInitContainers().stream(), probePod.getSpec().getContainers().stream())
                .toList()) {
            assertThat(each.getResources().getLimits()).as(each.getName()).containsKey("ephemeral-storage");
        }
    }

    /**
     * Every volume of the Pod on the node's disk has the size limit of its kind, the main container may use them all
     * with its logs, and every container has a disk limit, which a quota that counts {@code limits.ephemeral-storage}
     * requires of each.
     */
    private static void assertDiskBounded(Pod pod, String main, KubernetesPods.Size size) {
        var disk = pod.getSpec().getVolumes().stream()
                .filter(volume ->
                        volume.getEmptyDir() != null && volume.getEmptyDir().getMedium() == null)
                .toList();
        assertThat(disk).as(main).hasSize(size.diskVolumes());
        assertThat(disk)
                .allSatisfy(volume -> assertThat(bytes(volume.getEmptyDir().getSizeLimit()))
                        .as(volume.getName())
                        .isEqualTo(size.volumeBytes()));
        long volumes = disk.size() * size.volumeBytes();
        assertThat(bytes(container(pod, main).getResources().getLimits().get("ephemeral-storage")))
                .as("all its volumes full, and its logs")
                .isEqualTo(size.diskLimit())
                .isGreaterThan(volumes);
        for (Container each : Stream.concat(
                        pod.getSpec().getInitContainers().stream(), pod.getSpec().getContainers().stream())
                .toList()) {
            assertThat(each.getResources().getLimits()).as(each.getName()).containsKey("ephemeral-storage");
        }
    }

    @Test
    void shouldRankConversationsAboveReviewsAndEveryProbeWithTheReviews() {
        Map<String, String> review = Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_REVIEW);
        Map<String, String> host = Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_HOST);
        Map<String, String> turn = Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TURN);
        Map<String, String> turnProbe =
                Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TURN, KubernetesWorker.PROBE, "run");

        assertThat(pods.workspacePod(
                                new KubernetesPods.Workload("r", review, IMAGE, "agent"),
                                Map.of(),
                                List.of("node"),
                                LIMITS,
                                KubernetesPods.REVIEW)
                        .getSpec()
                        .getPriorityClassName())
                .isEqualTo("hephaestus-reviews");
        assertThat(pods.workspacePod(
                                new KubernetesPods.Workload("h", host, IMAGE, "agent"),
                                Map.of(),
                                List.of("node"),
                                LIMITS,
                                KubernetesPods.HOST)
                        .getSpec()
                        .getPriorityClassName())
                .isEqualTo("hephaestus-conversations");
        assertThat(pods.turnPod(new KubernetesPods.Workload("t", turn, IMAGE, "sandbox"), LIMITS)
                        .getSpec()
                        .getPriorityClassName())
                .isEqualTo("hephaestus-conversations");
        assertThat(pods.probePod(
                                new KubernetesPods.Workload("p", turnProbe, IMAGE, "agent"),
                                List.of("true"),
                                LIMITS,
                                60,
                                true)
                        .getSpec()
                        .getPriorityClassName())
                .as("a probe never displaces a person's conversation")
                .isEqualTo("hephaestus-reviews");
    }

    @Test
    void shouldGiveTurnSandboxAReadOnlyWorkspaceAndTheStagerAUserOfItsOwn() {
        Pod pod = pods.turnPod(new KubernetesPods.Workload("turn", Map.of(), IMAGE, "sandbox"), LIMITS);

        assertThat(pod.getSpec().getShareProcessNamespace()).isNotEqualTo(true);
        assertThat(pod.getSpec().getInitContainers())
                .extracting(Container::getName)
                .as("no staging or command reaches a sandbox before its isolation gate passed")
                .containsExactly(KubernetesPods.ISOLATION);
        assertRestricted(pod.getSpec().getInitContainers().getFirst(), KubernetesPods.SANDBOX_UID);
        Container sandbox = container(pod, KubernetesPods.SANDBOX);
        Container stager = container(pod, KubernetesPods.STAGER);
        assertRestricted(sandbox, KubernetesPods.SANDBOX_UID);
        assertRestricted(stager, KubernetesPods.STAGER_UID);
        assertThat(KubernetesPods.STAGER_UID).isNotEqualTo(KubernetesPods.SANDBOX_UID);
        assertThat(readOnly(sandbox, "/workspace")).isTrue();
        assertThat(readOnly(stager, "/workspace")).isFalse();
        assertThat(sandbox.getCommand())
                .containsExactly(limitedArgv(KubernetesPods.SANDBOX_INIT.toArray(String[]::new)));
        assertThat(stager.getCommand()).containsExactly(limitedArgv("sleep", "infinity"));
        assertThat(pod.getSpec().getSecurityContext().getFsGroup()).isEqualTo(KubernetesPods.STAGER_UID);
    }

    @Test
    void shouldSizeTheTurnSandboxFromMeasuredUseAndKeepItsWorkspaceOnDisk() {
        Pod pod = pods.turnPod(new KubernetesPods.Workload("turn", Map.of(), IMAGE, "sandbox"), LIMITS);

        Container sandbox = container(pod, KubernetesPods.SANDBOX);
        assertSized(sandbox, KubernetesPods.TURN);
        assertThat(Quantity.getAmountInBytes(container(pod, KubernetesPods.STAGER)
                                .getResources()
                                .getRequests()
                                .get("memory"))
                        .longValueExact())
                .as("the stager writes the workspace to disk, not into its memory")
                .isEqualTo(32L * 1024 * 1024);
        for (Container each : pod.getSpec().getContainers()) {
            var limits = each.getResources().getLimits();
            var requests = each.getResources().getRequests();
            assertThat(Quantity.getAmountInBytes(requests.get("cpu")).multiply(BigDecimal.TEN))
                    .as(each.getName())
                    .isEqualByComparingTo(Quantity.getAmountInBytes(limits.get("cpu")));
        }
        assertThat(pod.getSpec().getVolumes())
                .filteredOn(volume -> volume.getName().equals("workspace"))
                .singleElement()
                .satisfies(volume -> {
                    assertThat(volume.getEmptyDir().getMedium()).isNull();
                    assertThat(Quantity.getAmountInBytes(volume.getEmptyDir().getSizeLimit())
                                    .longValueExact())
                            .isEqualTo(SandboxLayout.TURN_WORKSPACE_BYTES);
                });
    }

    /** The container requests the memory and the local disk of its kind, and is limited to both. */
    private static void assertSized(Container container, KubernetesPods.Size size) {
        var requests = container.getResources().getRequests();
        var limits = container.getResources().getLimits();
        assertThat(bytes(requests.get("memory"))).as(container.getName()).isEqualTo(size.memoryRequest());
        assertThat(bytes(limits.get("memory"))).as(container.getName()).isEqualTo(size.memoryLimit());
        assertThat(bytes(requests.get("ephemeral-storage")))
                .as(container.getName())
                .isEqualTo(size.diskRequest());
        assertThat(bytes(limits.get("ephemeral-storage")))
                .as("no sandbox can fill the node's disk")
                .isEqualTo(size.diskLimit());
        assertThat(size.memoryRequest()).isLessThanOrEqualTo(size.memoryLimit());
    }

    private static long bytes(@Nullable Quantity quantity) {
        return Quantity.getAmountInBytes(Objects.requireNonNull(quantity)).longValueExact();
    }

    private static @Nullable String mountPath(Container container, String volume) {
        return container.getVolumeMounts().stream()
                .filter(mount -> mount.getName().equals(volume))
                .map(VolumeMount::getMountPath)
                .findFirst()
                .orElse(null);
    }

    @Test
    void shouldStartEveryCommandThroughTheRlimitsWithItsArgumentsUnchanged() {
        List<String> hostile = List.of("sh", "-c", "ulimit -Hn 65536; echo $HOME", "--nofile=65536", "a b", "'\"", "");

        List<String> argv = KubernetesPods.limited(hostile);

        assertThat(argv)
                .containsExactly(
                        "/usr/bin/prlimit",
                        "--nofile=1024:1024",
                        "--core=0:0",
                        "--",
                        "sh",
                        "-c",
                        "ulimit -Hn 65536; echo $HOME",
                        "--nofile=65536",
                        "a b",
                        "'\"",
                        "");
    }

    @Test
    void shouldReachGatewayAtTheWorkersOwnAddress() {
        assertThat(worker.gatewayUrl()).isEqualTo("http://[fd00::7]:8081");
    }

    @Test
    void shouldStartListAndRemoveTurnSandbox() throws Exception {
        var sandboxes = new KubernetesExecSandboxes(
                api(), pods, worker, IMAGE, liveWorkers, new SandboxExits(pods, new SimpleMeterRegistry()));
        String sandboxId = UUID.randomUUID().toString();
        CompletableFuture<String> created =
                CompletableFuture.supplyAsync(() -> sandboxes.create(sandboxId, LIMITS), background);
        String name = "hephaestus-turn-" + sandboxId;
        setStatus(
                SANDBOXES,
                name,
                "Running",
                List.of(),
                List.of(running(KubernetesPods.SANDBOX), running(KubernetesPods.STAGER)));

        assertThat(created.get(10, TimeUnit.SECONDS)).isEqualTo(name);
        Pod pod = Objects.requireNonNull(
                api().pods().inNamespace(SANDBOXES).withName(name).get());
        assertThat(pod.getMetadata().getLabels())
                .containsEntry(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TURN)
                .containsEntry(KubernetesWorker.WORKER_POD, workerName)
                .containsEntry(KubernetesWorker.SANDBOX_ID, sandboxId)
                .containsEntry(KubernetesWorker.PART_OF, "hephaestus");
        assertThat(sandboxes.workerInstance()).isEqualTo(workerName);
        assertThat(sandboxes.list()).containsExactly(new ExecSandboxes.Listed(name, sandboxId, workerName, false));

        sandboxes.remove(name);
        sandboxes.remove(name);

        assertThat(api().pods().inNamespace(SANDBOXES).withName(name).get()).isNull();
        sandboxes.close();
    }

    @Test
    void shouldEndReviewAsCancelledAndDeleteItsJobAndPodWhenCancelledWhileItsPodRuns() throws Exception {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId)), background);
        String child = startWatchedChild(name);

        adapter.cancel(jobId);

        assertThat(storedPod(child).getSpec().getActiveDeadlineSeconds())
                .as("the kubelet ends the Pod as at its deadline, and keeps its logs")
                .isEqualTo(1L);
        setStatus(
                SANDBOXES,
                child,
                "Failed",
                List.of(exited(KubernetesPods.INITIALIZER, 0)),
                List.of(exited(KubernetesPods.RUNTIME, SandboxLayout.EXIT_KILLED)));
        assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(SandboxCancelledException.class);
        assertThat(job(name)).isNull();
        assertThat(api().pods().inNamespace(SANDBOXES).withName(child).get()).isNull();
        assertThat(executions("cancelled")).isEqualTo(1);
    }

    @Test
    void shouldEndTheReviewAsAnInfrastructureFailureByItsWorkDeadlineWhenItsJobCreatesNoPod() {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        var shortWork =
                new ResourceLimits(LIMITS.memoryBytes(), LIMITS.cpus(), LIMITS.pidsLimit(), Duration.ofSeconds(3));
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId, shortWork)), background);
        awaitJob(name);

        // The fixed wait for a Pod is two minutes; the remaining work time, three seconds, bounds it here.
        assertThatThrownBy(() -> execution.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(SandboxInfrastructureException.class)
                .hasMessageContaining("created no Pod");
        assertThat(job(name)).isNull();
        assertThatThrownBy(() -> sessions.require(jobId, "Bearer job-token"))
                .as("the attempt's gateway session is closed")
                .isInstanceOf(ResponseStatusException.class);
        assertThat(executions("failure")).isEqualTo(1);
        assertThat(executions("timeout")).isZero();
    }

    @Test
    void shouldEndReviewAsCancelledAndDeleteItsJobWhenCancelledBeforeItsPodExists() {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId)), background);
        awaitJob(name);

        adapter.cancel(jobId);

        assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(SandboxCancelledException.class);
        assertThat(job(name)).isNull();
        assertThat(api().pods().inNamespace(SANDBOXES).list().getItems()).isEmpty();
    }

    @Test
    void shouldRefuseReviewAndKeepTheJobWhenItsAttemptAlreadyHasOne() {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        Job existing = api().batch()
                .v1()
                .jobs()
                .inNamespace(SANDBOXES)
                .resource(new JobBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .withNamespace(SANDBOXES)
                        .endMetadata()
                        .withNewSpec()
                        .withNewTemplate()
                        .withNewSpec()
                        .withRestartPolicy("Never")
                        .addNewContainer()
                        .withName("c")
                        .withImage(IMAGE)
                        .endContainer()
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build())
                .create();

        assertThatThrownBy(() -> adapter.execute(spec(jobId)))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("already exists");
        assertThat(job(name))
                .as("the Job of the attempt is neither adopted nor deleted")
                .extracting(stored -> stored.getMetadata().getUid())
                .isEqualTo(existing.getMetadata().getUid());
    }

    @Test
    void shouldFailReviewWhoseWorkspaceDoesNotInitialize() throws Exception {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId)), background);
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        String child = startWatchedChild(name);
        setStatus(SANDBOXES, child, "Failed", List.of(exited(KubernetesPods.INITIALIZER, 1)), List.of());

        assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(SandboxException.class)
                .hasMessageContaining("initialization failed");
        assertThat(job(name)).isNull();
        assertThat(api().pods().inNamespace(SANDBOXES).withName(child).get()).isNull();
    }

    @Test
    void shouldEndAsAnInfrastructureFailureWhenTheNetworkPolicyNeverFiltersTheReviewPod() throws Exception {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId)), background);
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        String child = startWatchedChild(name);
        setStatus(SANDBOXES, child, "Failed", List.of(exited(KubernetesPods.ISOLATION, 1)), List.of());

        assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                .as("the cluster's failure, not one of the review: the attempt is tried again")
                .hasCauseInstanceOf(SandboxInfrastructureException.class)
                .hasMessageContaining("network policy");
        assertThat(job(name)).isNull();
        assertThat(api().pods().inNamespace(SANDBOXES).withName(child).get()).isNull();
    }

    @Test
    void shouldReportUploadFailureExitWithoutResultsAsDockerDoes() throws Exception {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId)), background);
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        String child = startWatchedChild(name);
        setStatus(
                SANDBOXES,
                child,
                "Failed",
                List.of(exited(KubernetesPods.INITIALIZER, 0)),
                List.of(exited(KubernetesPods.RUNTIME, SandboxLayout.EXIT_UPLOAD_FAILED)));

        SandboxResult result = execution.get(10, TimeUnit.SECONDS);

        assertThat(result.exitCode()).isEqualTo(SandboxLayout.EXIT_UPLOAD_FAILED);
        assertThat(result.outputFiles()).isEmpty();
        assertThat(result.timedOut()).isFalse();
        assertThat(job(name)).isNull();
        assertThat(api().pods().inNamespace(SANDBOXES).withName(child).get()).isNull();
    }

    @Test
    void shouldFailTheAttemptAsTheClustersFailureAndRemoveTheJobWhenKubernetesEvictsTheReviewPod() throws Exception {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId)), background);
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        String child = startWatchedChild(name);
        Pod pod = awaitPod(SANDBOXES, child);
        pod.setStatus(new PodStatusBuilder()
                .withPhase("Failed")
                .withReason("Evicted")
                .withConditions(new PodConditionBuilder()
                        .withType("DisruptionTarget")
                        .withStatus("True")
                        .withReason("TerminationByKubelet")
                        .build())
                .withInitContainerStatuses(List.of(exited(KubernetesPods.INITIALIZER, 0)))
                .withContainerStatuses(List.of(exited(KubernetesPods.RUNTIME, 137)))
                .build());
        api().pods().inNamespace(SANDBOXES).resource(pod).update();

        assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                .as("an eviction is no exit of the review: the ledger tries the attempt again")
                .hasCauseInstanceOf(SandboxInfrastructureException.class)
                .hasMessageContaining("TerminationByKubelet");
        assertThat(job(name)).isNull();
        assertThat(api().pods().inNamespace(SANDBOXES).withName(child).get()).isNull();
    }

    @Test
    void shouldReportTheWorkTimeoutExitAsATimeoutAndDeleteTheJobWhenTheRunnerReachesItsDeadline() throws Exception {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId)), background);
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        String child = startWatchedChild(name);
        setStatus(
                SANDBOXES,
                child,
                "Failed",
                List.of(exited(KubernetesPods.INITIALIZER, 0)),
                List.of(exited(KubernetesPods.RUNTIME, SandboxLayout.EXIT_WORK_TIMEOUT)));

        SandboxResult result = execution.get(10, TimeUnit.SECONDS);

        assertThat(result.exitCode()).isEqualTo(SandboxLayout.EXIT_WORK_TIMEOUT);
        assertThat(result.timedOut()).isTrue();
        assertThat(executions("timeout")).isEqualTo(1);
        assertThat(executions("success")).isZero();
        assertThat(job(name)).isNull();
        assertThat(api().pods().inNamespace(SANDBOXES).withName(child).get()).isNull();
    }

    @Test
    void shouldUseTheClustersDefaultRuntimeAndRuntimeDefaultSeccompWhenNoneIsNamed() {
        var defaults = new KubernetesWorker(
                new KubernetesSandboxProperties(
                        SANDBOXES, workerName, "10.42.0.7", "", "", "", "", "", "", Duration.ofMinutes(15)),
                WORKER_ID,
                8081);
        Pod pod = new KubernetesPods(api(), defaults, 10, ImagePullPolicy.IF_NOT_PRESENT)
                .turnPod(new KubernetesPods.Workload("turn", Map.of(), IMAGE, "sandbox"), LIMITS);

        assertThat(pod.getSpec().getRuntimeClassName()).isNull();
        assertThat(pod.getSpec().getSecurityContext().getSeccompProfile().getType())
                .isEqualTo("RuntimeDefault");
        assertThat(pod.getSpec().getSecurityContext().getSeccompProfile().getLocalhostProfile())
                .isNull();
    }

    @Test
    void shouldAdmitAsManyReviewsAsTheSandboxQuotaHolds() {
        assertThat(backend().roomFor(LIMITS))
                .as("no quota, no bound of its own")
                .isEqualTo(Integer.MAX_VALUE);

        quotaWithRoomForTwoReviews();

        assertThat(backend().roomFor(LIMITS))
                .as("1280 MiB free hold two reviews that request 640 MiB")
                .isEqualTo(2);
    }

    @Test
    void shouldCountALaunchedReviewAgainstTheRoomBeforeItsPodAppears() {
        quotaWithRoomForTwoReviews();
        KubernetesSandboxBackend reviews = backend();
        UUID jobId = UUID.randomUUID();

        reviews.launch(new SandboxBackend.Launch(
                SandboxBackend.Kind.REVIEW,
                jobId,
                1,
                IMAGE,
                List.of("node", "run.ts"),
                LIMITS,
                Instant.now().plus(Duration.ofMinutes(7)),
                gateway -> Map.of()));

        assertThat(storedJob(KubernetesSandboxBackend.jobName(jobId, 1))).isNotNull();
        assertThat(reviews.roomFor(LIMITS))
                .as("the quota does not count the Job until the Job controller creates its Pod")
                .isEqualTo(1);
    }

    @Test
    void shouldChargeTheOverheadThatASandboxPodOfTheRuntimeClassShows() {
        quotaWithRoomForTwoReviews();
        api().pods()
                .inNamespace(SANDBOXES)
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName("turn-with-overhead")
                        .withLabels(worker.labels(KubernetesWorker.KIND_TURN))
                        .endMetadata()
                        .withNewSpec()
                        .withRuntimeClassName("gvisor")
                        .withOverhead(Map.of("memory", new Quantity("512Mi")))
                        .addNewContainer()
                        .withName("sandbox")
                        .withImage(IMAGE)
                        .endContainer()
                        .endSpec()
                        .build())
                .create();

        assertThat(new KubernetesSandboxQuota(cache(), SANDBOXES, "gvisor", "").roomFor(LIMITS))
                .as("1280 MiB free hold one review of 640 MiB with 512 MiB of overhead")
                .isEqualTo(1);
        assertThat(new KubernetesSandboxQuota(cache(), SANDBOXES, "", "").roomFor(LIMITS))
                .as("the cluster's default runtime has no overhead")
                .isEqualTo(2);
    }

    @Test
    void shouldSendTheApiServerNoRequestWhenClaimPollsAskForRoom() {
        quotaWithRoomForTwoReviews();
        var quota = new KubernetesSandboxQuota(cache(), SANDBOXES, "", "");
        int before = Objects.requireNonNull(server).getRequestCount();

        for (int poll = 0; poll < 20; poll++) {
            assertThat(quota.roomFor(LIMITS)).isEqualTo(2);
        }

        assertThat(server.getRequestCount()).as("20 claim polls read the cache").isEqualTo(before);
    }

    @Test
    void shouldAdmitByTheNewQuotaWhenItChangesAfterTheCacheListed() {
        var quota = new KubernetesSandboxQuota(cache(), SANDBOXES, "", "");
        assertThat(quota.roomFor(LIMITS)).as("no quota yet").isEqualTo(Integer.MAX_VALUE);

        quotaWithRoomForTwoReviews();

        await().atMost(Duration.ofSeconds(10)).until(() -> quota.roomFor(LIMITS) == 2);
    }

    @Test
    void shouldSeeARunningReviewWhenItsJobHasAnActivePod() {
        api().batch()
                .v1()
                .jobs()
                .inNamespace(SANDBOXES)
                .resource(reviewJob("review-active", 1))
                .create();
        api().batch()
                .v1()
                .jobs()
                .inNamespace(SANDBOXES)
                .resource(reviewJob("review-refused", 0))
                .create();

        assertThat(backend().reviewsRunning()).isTrue();
    }

    @Test
    void shouldSeeNoRunningReviewWhenNoJobHasAnActivePod() {
        api().batch()
                .v1()
                .jobs()
                .inNamespace(SANDBOXES)
                .resource(reviewJob("review-waiting", 0))
                .create();

        assertThat(backend().reviewsRunning()).isFalse();
    }

    @Test
    void shouldBecomeCurrentWhenTheFirstQuotaListIsRefusedAndThenAllowed() throws InterruptedException {
        String list = "/api/v1/namespaces/" + SANDBOXES + "/resourcequotas?resourceVersion=0";
        Objects.requireNonNull(server)
                .expect()
                .get()
                .withPath(list)
                .andReturn(403, "{}")
                .once();

        var cache = new KubernetesSandboxCache(api(), SANDBOXES);
        caches.add(cache);

        await().atMost(Duration.ofSeconds(30)).until(cache::current);
        int lists = 0;
        RecordedRequest request;
        while ((request = server.takeRequest(100, TimeUnit.MILLISECONDS)) != null) {
            if (list.equals(request.getPath())) lists++;
        }
        assertThat(lists).as("the refused list, then the one that recovered").isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldAdmitAsWithoutAQuotaWhenTheCacheNoLongerWatches() {
        quotaWithRoomForTwoReviews();
        var cache = cache();
        var quota = new KubernetesSandboxQuota(cache, SANDBOXES, "", "");
        assertThat(quota.roomFor(LIMITS)).isEqualTo(2);

        cache.close();

        assertThat(quota.roomFor(LIMITS))
                .as("a cache that stopped does not decide; the start does, as before")
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(quota.neverFits(LIMITS)).isFalse();
    }

    @Test
    void shouldReportNotReadyWhenTheNamespaceCacheStopsWatching() {
        var cache = cache();
        HealthIndicator readiness =
                new KubernetesSandboxConfiguration().kubernetesSandboxHealthIndicator(cache, worker);
        assertThat(Objects.requireNonNull(readiness.health()).getStatus()).isEqualTo(Status.UP);

        cache.close();

        assertThat(Objects.requireNonNull(readiness.health()).getStatus())
                .as("no claim reads a cache that no longer follows the API server")
                .isEqualTo(Status.DOWN);
    }

    private void quotaWithRoomForTwoReviews() {
        api().resourceQuotas()
                .inNamespace(SANDBOXES)
                .resource(new ResourceQuotaBuilder()
                        .withNewMetadata()
                        .withName("hephaestus-sandbox-quota")
                        .endMetadata()
                        .withNewSpec()
                        .withHard(Map.of("requests.memory", new Quantity("4Gi")))
                        .endSpec()
                        .withNewStatus()
                        .withHard(Map.of("requests.memory", new Quantity("4Gi")))
                        .withUsed(Map.of("requests.memory", new Quantity("2816Mi")))
                        .endStatus()
                        .build())
                .create();
    }

    @Test
    void shouldStartNoSandboxBeforeTheIsolationProbePassed() {
        var unproved = new KubernetesWorker(properties, WORKER_ID, 8081);
        var unprovedPods = new KubernetesPods(api(), unproved, 10, ImagePullPolicy.IF_NOT_PRESENT);
        var adapter = new SandboxAttemptRunner(
                new KubernetesSandboxBackend(
                        unprovedPods,
                        new KubernetesSandboxJobs(api(), unproved),
                        unproved,
                        liveWorkers,
                        new SandboxExits(unprovedPods, new SimpleMeterRegistry()),
                        cache()),
                new SandboxWorkspaceManager(),
                sessions,
                meters);
        HealthIndicator readiness =
                new KubernetesSandboxConfiguration().kubernetesSandboxHealthIndicator(cache(), unproved);

        assertThat(adapter.ready()).as("the claim loops take no work").isFalse();
        assertThat(Objects.requireNonNull(readiness.health()).getStatus())
                .as("the worker is not ready")
                .isEqualTo(Status.DOWN);

        assertThatThrownBy(() -> adapter.execute(spec(UUID.randomUUID())))
                .isInstanceOf(SandboxInfrastructureException.class)
                .hasMessageContaining("isolation probe");
        try (var turns = new KubernetesExecSandboxes(
                api(),
                unprovedPods,
                unproved,
                IMAGE,
                liveWorkers,
                new SandboxExits(unprovedPods, new SimpleMeterRegistry()))) {
            assertThatThrownBy(() -> turns.create("turn", LIMITS)).isInstanceOf(SandboxInfrastructureException.class);
        }
        assertThat(api().batch().v1().jobs().inNamespace(SANDBOXES).list().getItems())
                .isEmpty();
        assertThat(api().pods().inNamespace(SANDBOXES).list().getItems()).isEmpty();

        unproved.proved(new KubernetesIsolationProbe.Proof(Instant.now(), List.of(), false, "", 256));

        assertThat(adapter.ready()).isTrue();
        assertThat(Objects.requireNonNull(readiness.health()).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void shouldRemoveTheJobsOfEndedReviewsAndTheHostsOfGoneWorkersWhenSwept() {
        UUID activeJob = UUID.randomUUID();
        String ended = KubernetesSandboxBackend.jobName(UUID.randomUUID(), 1);
        String active = KubernetesSandboxBackend.jobName(activeJob, 2);
        String activeOfGoneWorker = KubernetesSandboxBackend.jobName(activeJob, 1);
        String hostHere = "hephaestus-host-" + UUID.randomUUID();
        String hostOfGoneWorker = "hephaestus-host-" + UUID.randomUUID();
        createJob(ended, labels -> withJob(labels, UUID.randomUUID()));
        createJob(active, labels -> withJob(labels, activeJob));
        createJob(activeOfGoneWorker, labels -> gone(withJob(labels, activeJob)));
        createSandbox(hostHere, KubernetesWorker.KIND_HOST, KubernetesSandboxRuntimeTest::withSession);
        createSandbox(hostOfGoneWorker, KubernetesWorker.KIND_HOST, labels -> gone(withSession(labels)));
        createSandbox("turn-of-gone-worker", KubernetesWorker.KIND_TURN, KubernetesSandboxRuntimeTest::gone);
        var running = new ReviewJob();
        running.setId(activeJob);
        ReviewJobRepository jobs = mock(ReviewJobRepository.class);
        when(jobs.findByStatusIn(any())).thenReturn(List.of(running));
        var registry = new SimpleMeterRegistry();
        // An hour later, every object here is past the reconciler's grace.
        Clock later = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));

        new SandboxReconciler(jobs, backend(), registry, later).periodicReconciliation();

        assertThat(api().batch().v1().jobs().inNamespace(SANDBOXES).list().getItems())
                .extracting(job -> job.getMetadata().getName())
                .as("a review's Job follows its job; the ledger, not the worker, decides that it runs")
                .containsExactlyInAnyOrder(active, activeOfGoneWorker);
        assertThat(api().pods().inNamespace(SANDBOXES).list().getItems())
                .extracting(pod -> pod.getMetadata().getName())
                .as("turn sandboxes are their worker's, not the reconciler's")
                .containsExactlyInAnyOrder(hostHere, "turn-of-gone-worker");
        assertThat(registry.get(AgentMetrics.SANDBOX_RECONCILER_ORPHANED)
                        .tag("resource", "sandbox")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void shouldKeepAYoungJobOfAnUnknownJobWhenSwept() {
        createJob("job-young", labels -> withJob(labels, UUID.randomUUID()));
        ReviewJobRepository jobs = mock(ReviewJobRepository.class);
        when(jobs.findByStatusIn(any())).thenReturn(List.of());

        new SandboxReconciler(jobs, backend(), new SimpleMeterRegistry(), Clock.systemUTC()).periodicReconciliation();

        assertThat(job("job-young"))
                .as("an attempt that starts is not mistaken for an ended one")
                .isNotNull();
    }

    @Test
    void shouldCallNoHostsWorkerGoneWhileTheRegistryIsUnreadable() {
        createSandbox("host-of-gone-worker", KubernetesWorker.KIND_HOST, labels -> gone(withSession(labels)));
        WorkerRegistryRepository unreadable = mock(WorkerRegistryRepository.class);
        when(unreadable.findLiveWorkerIds(anyLong())).thenThrow(new IllegalStateException("database unavailable"));

        List<SandboxBackend.Listed> listed = new KubernetesSandboxBackend(
                        pods,
                        reviewJobs,
                        worker,
                        new KubernetesLiveWorkers(unreadable),
                        new SandboxExits(pods, new SimpleMeterRegistry()),
                        cache())
                .list();

        assertThat(listed)
                .singleElement()
                .satisfies(host -> assertThat(host.creatorGone()).isFalse());
    }

    @Test
    void shouldListTheJobsAndHostsOfEveryWorkerByTheirNames() {
        UUID jobId = UUID.randomUUID();
        createJob("hephaestus-review-" + jobId + "-3", labels -> {
            labels.put(KubernetesWorker.JOB_ATTEMPT, "3");
            return gone(withJob(labels, jobId));
        });
        createSandbox("host-here", KubernetesWorker.KIND_HOST, KubernetesSandboxRuntimeTest::withSession);

        List<SandboxBackend.Listed> listed = backend().list();

        assertThat(listed)
                .extracting(
                        sandbox -> sandbox.handle().id(),
                        SandboxBackend.Listed::kind,
                        SandboxBackend.Listed::attempt,
                        SandboxBackend.Listed::creatorGone)
                .containsExactlyInAnyOrder(
                        tuple("hephaestus-review-" + jobId + "-3", SandboxBackend.Kind.REVIEW, 3, false),
                        tuple("host-here", SandboxBackend.Kind.HOST, null, false));
        assertThat(listed).allSatisfy(sandbox -> assertThat(sandbox.created()).isNotNull());
    }

    @Test
    void shouldKillAndRemoveAReviewThatAnotherWorkerLaunchedByItsName() {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 1);
        createJob(name, labels -> gone(withJob(labels, jobId)));
        String child = startChild(name);
        var handle = new SandboxBackend.Handle(name);

        backend().kill(handle);
        assertThat(storedPod(child).getSpec().getActiveDeadlineSeconds()).isEqualTo(1L);

        backend().remove(handle);
        assertThat(job(name)).isNull();
        assertThat(api().pods().inNamespace(SANDBOXES).withName(child).get()).isNull();
    }

    @Test
    void shouldDeleteTheJobSoThatNoPodStartsWhenAnotherWorkerKillsAReviewBeforeItsPod() {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 1);
        createJob(name, labels -> gone(withJob(labels, jobId)));

        backend().kill(new SandboxBackend.Handle(name));

        assertThat(job(name)).isNull();
    }

    @Test
    void shouldBoundTheReviewJobByTheAttemptsUploadDeadline() {
        UUID jobId = UUID.randomUUID();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(7));

        backend()
                .launch(new SandboxBackend.Launch(
                        SandboxBackend.Kind.REVIEW,
                        jobId,
                        1,
                        IMAGE,
                        List.of("node", "run.ts"),
                        LIMITS,
                        deadline,
                        gateway -> Map.of()));

        long seconds =
                storedJob(KubernetesSandboxBackend.jobName(jobId, 1)).getSpec().getActiveDeadlineSeconds();
        long expected = Duration.ofMinutes(7)
                .plus(KubernetesSandboxBackend.DEADLINE_BACKSTOP)
                .toSeconds();
        assertThat(seconds).isBetween(expected - 5, expected);
    }

    @Test
    void shouldCallEveryOtherWorkerOfATurnSandboxGone() {
        createSandbox("turn-here", KubernetesWorker.KIND_TURN, labels -> labels);
        createSandbox("turn-of-gone-worker", KubernetesWorker.KIND_TURN, KubernetesSandboxRuntimeTest::gone);
        try (var sandboxes = new KubernetesExecSandboxes(
                api(), pods, worker, IMAGE, liveWorkers, new SandboxExits(pods, new SimpleMeterRegistry()))) {
            assertThat(sandboxes.list())
                    .extracting(ExecSandboxes.Listed::id, ExecSandboxes.Listed::workerGone)
                    .containsExactlyInAnyOrder(tuple("turn-here", false), tuple("turn-of-gone-worker", true));
        }
    }

    /** The labels as a worker process that no longer beats in the registry wrote them. */
    private static Map<String, String> gone(Map<String, String> labels) {
        labels.put(KubernetesWorker.WORKER_ID, "worker-process-gone");
        return labels;
    }

    private static Map<String, String> withSession(Map<String, String> labels) {
        labels.put(KubernetesWorker.SESSION_ID, UUID.randomUUID().toString());
        return labels;
    }

    static KubernetesIsolationProbe.Proof passed() {
        return new KubernetesIsolationProbe.Proof(Instant.now(), List.of(), false, "gvisor", 256);
    }

    // --- fixtures ---

    private KubernetesSandboxBackend backend() {
        return new KubernetesSandboxBackend(
                pods, reviewJobs, worker, liveWorkers, new SandboxExits(pods, new SimpleMeterRegistry()), cache());
    }

    private SandboxAttemptRunner adapter() {
        return new SandboxAttemptRunner(backend(), new SandboxWorkspaceManager(), sessions, meters);
    }

    private Pod storedPod(String name) {
        return Objects.requireNonNull(
                api().pods().inNamespace(SANDBOXES).withName(name).get(), () -> "Pod " + name + " exists");
    }

    private double executions(String outcome) {
        return meters.get(AgentMetrics.SANDBOX_EXECUTIONS)
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    /** A review Job of this installation, as the adapter writes one, with the labels that {@code labels} returns. */
    private Job createJob(String name, Function<Map<String, String>, Map<String, String>> labels) {
        Pod template = pods.workspacePod(
                new KubernetesPods.Workload(
                        name, labels.apply(worker.labels(KubernetesWorker.KIND_REVIEW)), IMAGE, "agent"),
                Map.of(),
                List.of("node", "run.ts"),
                LIMITS,
                KubernetesPods.REVIEW);
        return api().batch()
                .v1()
                .jobs()
                .inNamespace(SANDBOXES)
                .resource(reviewJobs.job(template, 600L))
                .create();
    }

    private static Map<String, String> withJob(Map<String, String> labels, UUID jobId) {
        labels.put(KubernetesWorker.JOB_ID, jobId.toString());
        return labels;
    }

    private @Nullable Job job(String name) {
        return api().batch().v1().jobs().inNamespace(SANDBOXES).withName(name).get();
    }

    private Job storedJob(String name) {
        return Objects.requireNonNull(job(name), () -> "Job " + name + " exists");
    }

    private Job awaitJob(String name) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Job job = job(name);
            if (job != null) {
                return job;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Job " + name + " was not created");
    }

    /**
     * Starts the Pod of {@code name}'s Job and returns once the review waits on a registered watch of it, so a status
     * change that follows cannot fall between the wait's list and its watch (see {@link #watching}).
     */
    private String startWatchedChild(String name) throws InterruptedException {
        watchedPod.set(name + "-child");
        String child = startChild(name);
        assertThat(watching.await(10, TimeUnit.SECONDS))
                .as("fixture condition: the review waits on a registered watch of its Pod")
                .isTrue();
        return child;
    }

    /** Creates the Pod that the Job controller would create for the attempt's Job. The mock runs no Job controller. */
    private String startChild(String name) {
        Job job = awaitJob(name);
        Pod child = new PodBuilder()
                .withNewMetadata()
                .withName(name + "-child")
                .withNamespace(SANDBOXES)
                .withLabels(job.getSpec().getTemplate().getMetadata().getLabels())
                .addToLabels(KubernetesSandboxJobs.JOB_NAME, name)
                .endMetadata()
                .withSpec(job.getSpec().getTemplate().getSpec())
                .build();
        return api().pods()
                .inNamespace(SANDBOXES)
                .resource(child)
                .create()
                .getMetadata()
                .getName();
    }

    private static SandboxSpec spec(UUID jobId) {
        return spec(jobId, LIMITS);
    }

    private static SandboxSpec spec(UUID jobId, ResourceLimits limits) {
        return new SandboxSpec(
                jobId,
                2,
                IMAGE,
                List.of("node", "run.ts"),
                Map.of(),
                "job-token",
                limits,
                null,
                Map.of("input.txt", "input".getBytes(StandardCharsets.UTF_8)),
                Map.of(),
                List.of());
    }

    private Job reviewJob(String name, int active) {
        return new JobBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(SANDBOXES)
                .withLabels(worker.labels(KubernetesWorker.KIND_REVIEW))
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .addNewContainer()
                .withName("c")
                .withImage(IMAGE)
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .withNewStatus()
                .withActive(active)
                .endStatus()
                .build();
    }

    private void createSandbox(String name, String kind, Function<Map<String, String>, Map<String, String>> labels) {
        Pod pod = new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(SANDBOXES)
                .withLabels(labels.apply(worker.labels(kind)))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("c")
                .withImage(IMAGE)
                .endContainer()
                .endSpec()
                .build();
        api().pods().inNamespace(SANDBOXES).resource(pod).create();
    }

    private Pod awaitPod(String namespace, String name) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Pod pod = api().pods().inNamespace(namespace).withName(name).get();
            if (pod != null) {
                return pod;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Pod " + name + " was not created");
    }

    /** What the kubelet would report; the mock server runs no container. */
    private void setStatus(
            String namespace,
            String name,
            String phase,
            List<ContainerStatus> initStatuses,
            List<ContainerStatus> statuses) {
        Pod pod = awaitPod(namespace, name);
        pod.setStatus(new PodStatusBuilder()
                .withPhase(phase)
                .withInitContainerStatuses(initStatuses)
                .withContainerStatuses(statuses)
                .build());
        api().pods().inNamespace(namespace).resource(pod).update();
    }

    private static ContainerStatus running(String name) {
        return new ContainerStatusBuilder()
                .withName(name)
                .withNewState()
                .withNewRunning()
                .endRunning()
                .endState()
                .build();
    }

    private static ContainerStatus exited(String name, int exitCode) {
        return new ContainerStatusBuilder()
                .withName(name)
                .withNewState()
                .withNewTerminated()
                .withExitCode(exitCode)
                .endTerminated()
                .endState()
                .build();
    }

    private static Container container(Pod pod, String name) {
        return pod.getSpec().getContainers().stream()
                .filter(container -> name.equals(container.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static boolean readOnly(Container container, String path) {
        VolumeMount mount = container.getVolumeMounts().stream()
                .filter(candidate -> path.equals(candidate.getMountPath()))
                .findFirst()
                .orElseThrow();
        return Boolean.TRUE.equals(mount.getReadOnly());
    }

    private static String[] limitedArgv(String... command) {
        List<String> argv = new ArrayList<>(KubernetesPods.RLIMITS);
        argv.addAll(List.of(command));
        return argv.toArray(String[]::new);
    }

    private static void assertRestricted(Container container, long uid) {
        var security = container.getSecurityContext();
        assertThat(security.getRunAsUser()).isEqualTo(uid);
        assertThat(security.getRunAsGroup()).isEqualTo(uid);
        assertThat(security.getRunAsNonRoot()).isTrue();
        assertThat(security.getPrivileged()).isFalse();
        assertThat(security.getAllowPrivilegeEscalation()).isFalse();
        assertThat(security.getReadOnlyRootFilesystem()).isTrue();
        assertThat(security.getCapabilities().getDrop()).containsExactly("ALL");
        assertThat(security.getCapabilities().getAdd()).isNullOrEmpty();
    }
}
