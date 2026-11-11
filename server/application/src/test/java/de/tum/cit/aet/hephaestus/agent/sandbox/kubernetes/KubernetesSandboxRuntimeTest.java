package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxAttemptRunner;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxReconciler;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInitializationException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxRoom;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.DeleteOptions;
import io.fabric8.kubernetes.api.model.EnvVar;
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
import io.fabric8.kubernetes.client.KubernetesClientException;
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
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final ResourceLimits LIMITS = new ResourceLimits(1.0, Duration.ofMinutes(5));

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

    /** The watches of {@link #watchedPod} that the mock registered: each wait on the Pod opens one. */
    private final AtomicInteger watches = new AtomicInteger();

    private final ExecutorService background = Executors.newCachedThreadPool();
    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private static final String WORKER_ID = "worker-process-1";

    private String workerName;
    private KubernetesSandboxProperties properties;
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
                    watches.incrementAndGet();
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
                Duration.ofMinutes(15),
                "");
        worker = new KubernetesWorker(properties, WORKER_ID, 8081);
        worker.proved(passed());
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
    void shouldRequireUserNamespacesForEveryPodEvenWithoutAProbeProof() {
        var unproved = new KubernetesWorker(properties, WORKER_ID, 8081);
        var manifests = new KubernetesPods(api(), unproved, 10, ImagePullPolicy.IF_NOT_PRESENT);
        var workload = new KubernetesPods.Workload("sandbox", Map.of(), IMAGE, "agent");
        assertThat(List.of(
                        manifests.workspacePod(
                                workload, Map.of(), List.of("node"), LIMITS, KubernetesPods.REVIEW, null),
                        manifests.toolsPod(workload, LIMITS, "nonce", Map.of()),
                        manifests.probePod(
                                workload,
                                List.of("true"),
                                LIMITS,
                                1024L * 1024 * 1024,
                                60,
                                KubernetesPods.SANDBOX_UID)))
                .allSatisfy(pod -> assertThat(pod.getSpec().getHostUsers()).isFalse());
    }

    @Test
    void shouldBuildReviewPodWithRestrictedContainersAndReadOnlyRuntimeWorkspace() {
        Pod pod = pods.workspacePod(
                new KubernetesPods.Workload("review", Map.of(), IMAGE, "agent"),
                Map.of("B", "2", "A", "1"),
                List.of("node", "run.ts"),
                LIMITS,
                KubernetesPods.REVIEW,
                null);

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
        Pod reviewPod = pods.workspacePod(
                new KubernetesPods.Workload("r", review, IMAGE, "agent"),
                Map.of(),
                List.of("node"),
                LIMITS,
                KubernetesPods.REVIEW,
                null);
        Pod toolsPod = toolsPod(Map.of());

        assertDiskBounded(reviewPod, KubernetesPods.LOOP, KubernetesPods.REVIEW);
        assertDiskBounded(toolsPod, KubernetesPods.TOOLS, KubernetesPods.TOOLS_POD);
        Pod probePod = pods.probePod(
                new KubernetesPods.Workload("p", review, IMAGE, "agent"),
                List.of("true"),
                LIMITS,
                1024L * 1024 * 1024,
                60,
                KubernetesPods.SANDBOX_UID);
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
        Map<String, String> tools = Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TOOLS);
        Map<String, String> toolsProbe =
                Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TOOLS, KubernetesWorker.PROBE, "run");

        assertThat(pods.workspacePod(
                                new KubernetesPods.Workload("r", review, IMAGE, "agent"),
                                Map.of(),
                                List.of("node"),
                                LIMITS,
                                KubernetesPods.REVIEW,
                                null)
                        .getSpec()
                        .getPriorityClassName())
                .isEqualTo("hephaestus-reviews");
        assertThat(toolsPod(tools).getSpec().getPriorityClassName()).isEqualTo("hephaestus-conversations");
        assertThat(pods.probePod(
                                new KubernetesPods.Workload("p", toolsProbe, IMAGE, "agent"),
                                List.of("true"),
                                LIMITS,
                                1024L * 1024 * 1024,
                                60,
                                KubernetesPods.SANDBOX_UID)
                        .getSpec()
                        .getPriorityClassName())
                .as("a probe never displaces a person's conversation")
                .isEqualTo("hephaestus-reviews");
    }

    @Test
    void shouldRunOnlyTheToolsProgramInAToolsPodOnTcpWithItsNonceAndNoCredential() {
        Pod pod = pods.toolsPod(
                new KubernetesPods.Workload("tools", Map.of(), IMAGE, "tools"),
                LIMITS,
                "the-nonce",
                Map.of("GIT_TERMINAL_PROMPT", "0"));

        assertThat(pod.getSpec().getInitContainers())
                .extracting(Container::getName)
                .as("no call reaches a tools Pod before its isolation gate passed")
                .containsExactly(KubernetesPods.ISOLATION);
        assertThat(pod.getSpec().getContainers()).extracting(Container::getName).containsExactly(KubernetesPods.TOOLS);
        Container tools = container(pod, KubernetesPods.TOOLS);
        assertRestricted(tools, KubernetesPods.TOOLS_UID);
        assertThat(tools.getCommand())
                .containsExactly(limitedArgv(KubernetesPods.TOOLS_COMMAND.toArray(String[]::new)));
        assertThat(tools.getEnv())
                .extracting(EnvVar::getName, EnvVar::getValue)
                .containsExactlyInAnyOrder(
                        tuple("GIT_TERMINAL_PROMPT", "0"),
                        tuple("TOOLS_NONCE", "the-nonce"),
                        tuple("TOOLS_PORT", "7100"),
                        tuple("TOOLS_ROLE", "conversation"));
        assertThat(tools.getPorts()).extracting(port -> port.getContainerPort()).containsExactly(ToolsPods.PORT);
        assertThat(tools.getVolumeMounts())
                .extracting(mount -> mount.getMountPath())
                .as("only its own /tmp: it has no workspace of a review and no socket")
                .containsExactly("/tmp");
        assertThat(pod.getSpec().getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getSpec().getSecurityContext().getFsGroup()).isEqualTo(KubernetesPods.TOOLS_UID);
    }

    @Test
    void shouldSizeTheToolsPodFromMeasuredUseAndKeepItsWorkspaceOnDisk() {
        Pod pod = toolsPod(Map.of());

        Container tools = container(pod, KubernetesPods.TOOLS);
        assertSized(tools, KubernetesPods.TOOLS_POD);
        // An idle tools Pod requests 25m of CPU, so that a rolling update fits a second worker's pool on one node; a
        // turn's short bursts run on the limits.
        assertThat(Quantity.getAmountInBytes(tools.getResources().getRequests().get("cpu")))
                .isEqualByComparingTo(new BigDecimal("0.025"));
        assertThat(Quantity.getAmountInBytes(tools.getResources().getLimits().get("cpu")))
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(pod.getSpec().getVolumes()).singleElement().satisfies(volume -> {
            assertThat(volume.getName()).isEqualTo("tools-tmp");
            assertThat(volume.getEmptyDir().getMedium()).isNull();
            assertThat(Quantity.getAmountInBytes(volume.getEmptyDir().getSizeLimit())
                            .longValueExact())
                    .isEqualTo(SandboxLayout.TURN_WORKSPACE_BYTES);
        });
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
    void shouldStartListAndRemoveAToolsPodAsAJob() throws Exception {
        var tools = toolsPods(worker, pods);
        String sandboxId = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        CompletableFuture<ToolsPods.Started> created =
                CompletableFuture.supplyAsync(() -> tools.create(sandboxId, nonce, LIMITS), background);
        String name = "hephaestus-tools-" + sandboxId;
        watchedPod.set(name + "-child");
        String child = startChild(name);
        // The Pod runs only once the create waits on a watch of it: the mock replays no event from before (watching).
        assertThat(watching.await(10, TimeUnit.SECONDS))
                .as("fixture condition: the create waits on a registered watch of its Pod")
                .isTrue();
        Pod pod = awaitPod(SANDBOXES, child);
        pod.setStatus(new PodStatusBuilder()
                .withPhase("Running")
                .withPodIP("10.42.3.9")
                .withContainerStatuses(running(KubernetesPods.TOOLS))
                .build());
        api().pods().inNamespace(SANDBOXES).resource(pod).update();

        ToolsPods.Started started = created.get(10, TimeUnit.SECONDS);
        assertThat(started.id()).isEqualTo(name);
        assertThat(started.host()).isEqualTo("10.42.3.9");
        assertThat(started.port()).isEqualTo(ToolsPods.PORT);
        assertThat(started.nonce()).isEqualTo(nonce);
        Job job = awaitJob(name);
        assertThat(job.getSpec().getBackoffLimit()).isZero();
        assertThat(job.getMetadata().getLabels())
                .containsEntry(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TOOLS)
                .containsEntry(KubernetesWorker.WORKER_POD, workerName)
                .containsEntry(KubernetesWorker.SANDBOX_ID, sandboxId)
                .containsEntry(KubernetesWorker.PART_OF, "hephaestus");
        assertThat(job.getSpec()
                        .getTemplate()
                        .getSpec()
                        .getContainers()
                        .getFirst()
                        .getEnv())
                .extracting(EnvVar::getName, EnvVar::getValue)
                .as("the nonce in the spec is the one the host checks")
                .contains(tuple("TOOLS_NONCE", started.nonce()));
        assertThat(tools.list()).singleElement().satisfies(listed -> {
            assertThat(listed.name()).isEqualTo(name);
            assertThat(listed.age().isNegative()).isFalse();
        });

        tools.remove(name);
        tools.remove(name);

        assertThat(api().batch()
                        .v1()
                        .jobs()
                        .inNamespace(SANDBOXES)
                        .withName(name)
                        .get())
                .isNull();
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

        assertThat(job(name))
                .as("the kill deletes the Job, and the garbage collector its Pod")
                .isNull();
        // The mock runs no garbage collector: the Pod ends as the kubelet would end it.
        setStatus(
                SANDBOXES,
                child,
                "Failed",
                List.of(exited(KubernetesPods.INITIALIZER, 0)),
                List.of(exited(KubernetesPods.LOOP, SandboxLayout.EXIT_KILLED)));
        assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(SandboxCancelledException.class);
        assertThat(job(name)).isNull();
        assertDeletedOnlyByItsJob(name);
        assertThat(executions("cancelled")).isEqualTo(1);
        assertThat(exits("deadline_exceeded"))
                .as("the kill records the exit before it deletes the Pod")
                .isEqualTo(1);
        assertThat(exits("completed"))
                .as("the removal does not record it a second time")
                .isZero();
    }

    @Test
    void shouldEndTheReviewAsAnInfrastructureFailureByItsWorkDeadlineWhenItsJobCreatesNoPod() {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        var shortWork = new ResourceLimits(LIMITS.cpus(), Duration.ofSeconds(3));
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
        assertDeletedOnlyByItsJob(name);
    }

    @Test
    void shouldKeepTheInitializersLogAndStopTheReviewByItsJobWhenItsInitializerOutrunsTheWorkDeadline()
            throws Exception {
        var adapter = adapter();
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 2);
        serveLogOnce(name + "-child", KubernetesPods.INITIALIZER, "cloning the workspace\n");
        var shortWork = new ResourceLimits(LIMITS.cpus(), Duration.ofSeconds(3));
        CompletableFuture<SandboxResult> execution =
                CompletableFuture.supplyAsync(() -> adapter.execute(spec(jobId, shortWork)), background);
        String child = startWatchedChild(name);
        setStatus(SANDBOXES, child, "Pending", List.of(running(KubernetesPods.INITIALIZER)), List.of());

        assertThatThrownBy(() -> execution.get(30, TimeUnit.SECONDS))
                .cause()
                .isInstanceOfSatisfying(SandboxInitializationException.class, failure -> {
                    assertThat(failure.timedOut()).isTrue();
                    assertThat(failure.exitCode()).isNull();
                    assertThat(failure.transcriptTail())
                            .as("read before the stop, which deletes the Pod with its log")
                            .isEqualTo("cloning the workspace");
                });
        assertThat(job(name)).isNull();
        assertDeletedOnlyByItsJob(name);
        assertThat(executions("timeout")).isEqualTo(1);
        assertThat(exits("deadline_exceeded")).isEqualTo(1);
        assertThat(exits("start_failed"))
                .as("a running initializer did not fail to start")
                .isZero();
    }

    /**
     * Another process kills the review: it deletes the Job with its Pod, which stops the runtime as a deadline does.
     * The runtime's exit, and the result it uploads in the grace period, come after the deletion starts.
     */
    @Test
    void shouldReportTheRuntimesOwnExitWhenAKillDeletesItsPodWhileItRuns() throws Exception {
        Killed killed = killedReview();

        // The runtime exits in its grace period; only then does the kubelet let the Pod go.
        setStatus(
                SANDBOXES,
                killed.pod(),
                "Running",
                List.of(exited(KubernetesPods.INITIALIZER, 0)),
                List.of(exited(KubernetesPods.LOOP, SandboxLayout.EXIT_UPLOAD_FAILED)));

        assertThat(killed.ended().get(10, TimeUnit.SECONDS))
                .as("the runtime's own exit, not the kill's")
                .isEqualTo(new SandboxBackend.Observation(false, SandboxLayout.EXIT_UPLOAD_FAILED));
        assertThat(job(killed.handle().id())).isNull();
    }

    @Test
    void shouldReportTheKillWhenThePodGoesBeforeItsRuntimeReportsAnExit() throws Exception {
        Killed killed = killedReview();

        release(killed.pod());

        assertThat(killed.ended().get(10, TimeUnit.SECONDS))
                .isEqualTo(new SandboxBackend.Observation(false, SandboxLayout.EXIT_KILLED));
    }

    /** A killed review: its handle, its held Pod, and the observation of its end that it still waits for. */
    private record Killed(
            SandboxBackend.Handle handle, String pod, CompletableFuture<SandboxBackend.Observation> ended) {}

    /**
     * A review whose runtime runs, killed by another process while the review waits for its end. Returns once the
     * review waits again, for the stopped runtime's exit.
     */
    private Killed killedReview() {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 1);
        var reviews = backend();
        SandboxBackend.Handle handle = reviews.launch(new SandboxBackend.Launch(
                SandboxBackend.Kind.REVIEW,
                jobId,
                1,
                IMAGE,
                List.of("node", "run.ts"),
                LIMITS,
                Instant.now().plus(Duration.ofMinutes(5)),
                gateway -> Map.of(),
                false));
        watchedPod.set(name + "-child");
        String child = startChild(name);
        // As the kubelet holds a Pod until its containers stopped: the deletion only marks it.
        Pod held = awaitPod(SANDBOXES, child);
        held.getMetadata().setFinalizers(List.of("hephaestus.build/test-hold"));
        api().pods().inNamespace(SANDBOXES).resource(held).update();
        setStatus(
                SANDBOXES,
                child,
                "Running",
                List.of(exited(KubernetesPods.INITIALIZER, 0)),
                List.of(running(KubernetesPods.LOOP)));
        CompletableFuture<SandboxBackend.Observation> ended = CompletableFuture.supplyAsync(
                () -> reviews.observe(
                        handle, SandboxBackend.Stage.ENDED, Instant.now().plusSeconds(60)),
                background);
        await().atMost(Duration.ofSeconds(10)).until(() -> watches.get() >= 1);

        backend().kill(handle);
        api().pods().inNamespace(SANDBOXES).withName(child).delete();
        assertThat(awaitPod(SANDBOXES, child).getMetadata().getDeletionTimestamp())
                .as("fixture condition: the deletion marks the held Pod")
                .isNotNull();

        await().atMost(Duration.ofSeconds(10))
                .alias("the review waits for the stopped runtime's exit")
                .until(() -> watches.get() >= 2);
        return new Killed(handle, child, ended);
    }

    /** Lets a held Pod go, as the kubelet does once its containers stopped. */
    private void release(String pod) {
        Pod held = awaitPod(SANDBOXES, pod);
        held.getMetadata().setFinalizers(List.of());
        api().pods().inNamespace(SANDBOXES).resource(held).update();
    }

    @Test
    void shouldKeepTheRuntimesLogAndStopTheReviewByItsJobWhenItOutrunsItsDeadline() {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 1);
        serveLogOnce(name + "-child", KubernetesPods.LOOP, "practice.started\n");
        var reviews = backend();
        SandboxBackend.Handle handle = reviews.launch(new SandboxBackend.Launch(
                SandboxBackend.Kind.REVIEW,
                jobId,
                1,
                IMAGE,
                List.of("node", "run.ts"),
                LIMITS,
                Instant.now().plus(Duration.ofMinutes(5)),
                gateway -> Map.of(),
                false));
        String child = startChild(name);
        setStatus(
                SANDBOXES,
                child,
                "Running",
                List.of(exited(KubernetesPods.INITIALIZER, 0)),
                List.of(running(KubernetesPods.LOOP)));

        SandboxBackend.Observation ended = reviews.observe(
                handle, SandboxBackend.Stage.ENDED, Instant.now().plusSeconds(1));

        assertThat(ended).isEqualTo(new SandboxBackend.Observation(true, null));
        assertThat(job(name)).isNull();
        assertThat(reviews.transcript(handle, SandboxBackend.Container.RUNTIME))
                .as("kept from before the stop: the log API serves it once here")
                .isEqualTo("practice.started\n");
        reviews.remove(handle);
        assertDeletedOnlyByItsJob(name);
        assertThat(exits("deadline_exceeded")).isEqualTo(1);
        assertThat(exits("completed"))
                .as("the removal does not record the exit a second time")
                .isZero();
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
        assertDeletedOnlyByItsJob(name);
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
        assertDeletedOnlyByItsJob(name);
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
                List.of(exited(KubernetesPods.LOOP, SandboxLayout.EXIT_UPLOAD_FAILED)));

        SandboxResult result = execution.get(10, TimeUnit.SECONDS);

        assertThat(result.exitCode()).isEqualTo(SandboxLayout.EXIT_UPLOAD_FAILED);
        assertThat(result.outputFiles()).isEmpty();
        assertThat(result.timedOut()).isFalse();
        assertThat(job(name)).isNull();
        assertDeletedOnlyByItsJob(name);
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
                .withContainerStatuses(List.of(exited(KubernetesPods.LOOP, 137)))
                .build());
        api().pods().inNamespace(SANDBOXES).resource(pod).update();

        assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                .as("an eviction is no exit of the review: the ledger tries the attempt again")
                .hasCauseInstanceOf(SandboxInfrastructureException.class)
                .hasMessageContaining("TerminationByKubelet");
        assertThat(job(name)).isNull();
        assertDeletedOnlyByItsJob(name);
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
                List.of(exited(KubernetesPods.LOOP, SandboxLayout.EXIT_WORK_TIMEOUT)));

        SandboxResult result = execution.get(10, TimeUnit.SECONDS);

        assertThat(result.exitCode()).isEqualTo(SandboxLayout.EXIT_WORK_TIMEOUT);
        assertThat(result.timedOut()).isTrue();
        assertThat(executions("timeout")).isEqualTo(1);
        assertThat(executions("success")).isZero();
        assertThat(job(name)).isNull();
        assertDeletedOnlyByItsJob(name);
    }

    @Test
    void shouldUseTheClustersDefaultRuntimeAndRuntimeDefaultSeccompWhenNoneIsNamed() {
        var defaults = new KubernetesWorker(
                new KubernetesSandboxProperties(
                        SANDBOXES, workerName, "10.42.0.7", "", "", "", "", "", "", Duration.ofMinutes(15), ""),
                WORKER_ID,
                8081);
        Pod pod = new KubernetesPods(api(), defaults, 10, ImagePullPolicy.IF_NOT_PRESENT)
                .toolsPod(new KubernetesPods.Workload("tools", Map.of(), IMAGE, "tools"), LIMITS, "n", Map.of());

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
                .as("1000 MiB free hold two reviews that request 384 MiB")
                .isEqualTo(2);
    }

    @Test
    void shouldPreserveUnknownRoomWhenTheQuotaWatchIsUnavailable() {
        var unavailable = mock(KubernetesSandboxCache.class);
        when(unavailable.quotas()).thenThrow(new IllegalStateException("quota watch unavailable"));
        var reviews = new KubernetesSandboxBackend(
                pods, reviewJobs, worker, new SandboxExits(pods, new SimpleMeterRegistry()), unavailable);

        assertThat(reviews.roomFor(LIMITS)).isEqualTo(SandboxRoom.UNKNOWN_ROOM);
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
                gateway -> Map.of(),
                false));

        assertThat(storedJob(KubernetesSandboxBackend.jobName(jobId, 1))).isNotNull();
        assertThat(reviews.roomFor(LIMITS))
                .as("the quota does not count the Job until the Job controller creates its Pod")
                .isEqualTo(1);
    }

    @Test
    void shouldObserveTheReviewLoopStartingAndEndingFromTheKubernetesWatch() {
        var reviews = backend();
        var handle = reviews.launch(new SandboxBackend.Launch(
                SandboxBackend.Kind.REVIEW,
                UUID.randomUUID(),
                1,
                IMAGE,
                List.of("node", "run.ts"),
                LIMITS,
                Instant.now().plus(Duration.ofMinutes(7)),
                gateway -> Map.of(),
                false));
        assertThat(reviews.running(handle)).isFalse();
        String child = startChild(handle.id());
        setStatus(
                SANDBOXES,
                child,
                "Pending",
                List.of(exited(KubernetesPods.ISOLATION, 0), exited(KubernetesPods.INITIALIZER, 0)),
                List.of());
        reviews.observe(handle, SandboxBackend.Stage.INITIALIZED, Instant.now().plusSeconds(10));
        assertThat(reviews.running(handle)).isFalse();

        setStatus(
                SANDBOXES,
                child,
                "Running",
                List.of(exited(KubernetesPods.ISOLATION, 0), exited(KubernetesPods.INITIALIZER, 0)),
                List.of(running(KubernetesPods.LOOP)));
        await().atMost(Duration.ofSeconds(10)).until(() -> reviews.running(handle));

        setStatus(
                SANDBOXES,
                child,
                "Succeeded",
                List.of(exited(KubernetesPods.ISOLATION, 0), exited(KubernetesPods.INITIALIZER, 0)),
                List.of(exited(KubernetesPods.LOOP, 0)));
        await().atMost(Duration.ofSeconds(10)).until(() -> !reviews.running(handle));
    }

    @ParameterizedTest
    @EnumSource(SandboxBackend.Kind.class)
    void shouldKeepWorkerRenewalActiveWhenTheSandboxWatchIsUnavailable(SandboxBackend.Kind kind) {
        var unavailable = mock(KubernetesSandboxCache.class);
        when(unavailable.pod(any())).thenThrow(new KubernetesClientException("watch unavailable"));
        var reviews = new KubernetesSandboxBackend(
                pods, reviewJobs, worker, new SandboxExits(pods, new SimpleMeterRegistry()), unavailable);
        var handle = reviews.launch(new SandboxBackend.Launch(
                kind,
                UUID.randomUUID(),
                1,
                IMAGE,
                List.of("node", "run.ts"),
                LIMITS,
                Instant.now().plus(Duration.ofMinutes(7)),
                gateway -> Map.of(),
                false));
        if (kind == SandboxBackend.Kind.REVIEW) {
            String child = startChild(handle.id());
            setStatus(
                    SANDBOXES,
                    child,
                    "Pending",
                    List.of(exited(KubernetesPods.ISOLATION, 0), exited(KubernetesPods.INITIALIZER, 0)),
                    List.of());
            reviews.observe(
                    handle, SandboxBackend.Stage.INITIALIZED, Instant.now().plusSeconds(10));
        }

        assertThat(reviews.running(handle)).isFalse();
    }

    @Test
    void shouldChargeTheOverheadThatASandboxPodOfTheRuntimeClassShows() {
        quotaWithRoomForTwoReviews();
        api().pods()
                .inNamespace(SANDBOXES)
                .resource(new PodBuilder()
                        .withNewMetadata()
                        .withName("tools-with-overhead")
                        .withLabels(worker.labels(KubernetesWorker.KIND_TOOLS))
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
                .as("1000 MiB free hold one review of 384 MiB with 512 MiB of overhead")
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
    void shouldKeepReviewsWaitingWhenTheCacheNoLongerWatches() {
        quotaWithRoomForTwoReviews();
        var cache = cache();
        var quota = new KubernetesSandboxQuota(cache, SANDBOXES, "", "");
        assertThat(quota.roomFor(LIMITS)).isEqualTo(2);

        cache.close();

        assertThat(quota.roomFor(LIMITS))
                .as("a cache that stopped has no observation, so reviews wait instead of starting without one")
                .isEqualTo(SandboxRoom.UNKNOWN_ROOM);
        assertThat(quota.neverFits(LIMITS))
                .as("waiting is not a quota that can never fit")
                .isFalse();
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
                        .withUsed(Map.of("requests.memory", new Quantity("3096Mi")))
                        .endStatus()
                        .build())
                .create();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldStartNoSandboxBeforeTheIsolationProbePassed(boolean probeRanWithoutUserNamespaces) {
        var unproved = new KubernetesWorker(properties, WORKER_ID, 8081);
        if (probeRanWithoutUserNamespaces) {
            unproved.proved(new KubernetesIsolationProbe.Proof(Instant.now(), List.of(), false, "gvisor", 256));
        }
        var unprovedPods = new KubernetesPods(api(), unproved, 10, ImagePullPolicy.IF_NOT_PRESENT);
        var adapter = new SandboxAttemptRunner(
                new KubernetesSandboxBackend(
                        unprovedPods,
                        new KubernetesSandboxJobs(api(), unproved),
                        unproved,
                        new SandboxExits(unprovedPods, new SimpleMeterRegistry()),
                        cache()),
                new SandboxWorkspaceManager(Path.of(System.getProperty("java.io.tmpdir"))),
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
        assertThatThrownBy(() -> toolsPods(unproved, unprovedPods).create("tools", "nonce", LIMITS))
                .isInstanceOf(SandboxInfrastructureException.class);
        assertThat(api().batch().v1().jobs().inNamespace(SANDBOXES).list().getItems())
                .isEmpty();
        assertThat(api().pods().inNamespace(SANDBOXES).list().getItems()).isEmpty();

        unproved.proved(new KubernetesIsolationProbe.Proof(Instant.now(), List.of(), true, "", 256));

        assertThat(adapter.ready()).isTrue();
        assertThat(Objects.requireNonNull(readiness.health()).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void shouldRemoveTheJobsOfEndedReviewsAndNoToolsPodWhenSwept() {
        UUID activeJob = UUID.randomUUID();
        String ended = KubernetesSandboxBackend.jobName(UUID.randomUUID(), 1);
        String active = KubernetesSandboxBackend.jobName(activeJob, 2);
        String activeOfGoneWorker = KubernetesSandboxBackend.jobName(activeJob, 1);
        createJob(ended, labels -> withJob(labels, UUID.randomUUID()));
        createJob(active, labels -> withJob(labels, activeJob));
        createJob(activeOfGoneWorker, labels -> gone(withJob(labels, activeJob)));
        createSandbox("tools-of-gone-worker", KubernetesWorker.KIND_TOOLS, KubernetesSandboxRuntimeTest::gone);
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
                .as("the reap of the tools Pod rows removes tools Pods, not the reconciler")
                .containsExactly("tools-of-gone-worker");
        assertThat(registry.get(AgentMetrics.SANDBOX_RECONCILER_ORPHANED)
                        .tag("resource", "sandbox")
                        .counter()
                        .count())
                .isEqualTo(1);
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
    void shouldListTheReviewJobsOfEveryWorkerByTheirNames() {
        UUID jobId = UUID.randomUUID();
        createJob("hephaestus-review-" + jobId + "-3", labels -> {
            labels.put(KubernetesWorker.JOB_ATTEMPT, "3");
            return gone(withJob(labels, jobId));
        });
        createToolsJob("hephaestus-tools-here", labels -> labels);

        List<SandboxBackend.Listed> listed = backend().list();

        assertThat(listed)
                .extracting(
                        sandbox -> sandbox.handle().id(), SandboxBackend.Listed::kind, SandboxBackend.Listed::attempt)
                .containsExactly(tuple("hephaestus-review-" + jobId + "-3", SandboxBackend.Kind.REVIEW, 3));
        assertThat(listed).allSatisfy(sandbox -> assertThat(sandbox.created()).isNotNull());
    }

    @Test
    void shouldNotListAReviewJobThatIsBeingDeleted() {
        UUID jobId = UUID.randomUUID();
        String name = "hephaestus-review-" + jobId + "-1";
        createJob(name, labels -> gone(withJob(labels, jobId)));
        // A finalizer keeps the Job after its delete, as a Foreground delete keeps it until its Pod is gone.
        api().batch()
                .v1()
                .jobs()
                .inNamespace(SANDBOXES)
                .withName(name)
                .edit(job -> new JobBuilder(job)
                        .editMetadata()
                        .addToFinalizers("hephaestus.build/test-hold")
                        .endMetadata()
                        .build());
        api().batch().v1().jobs().inNamespace(SANDBOXES).withName(name).delete();
        assertThat(job(name)).as("the finalizer keeps the Job").isNotNull();

        assertThat(backend().list()).isEmpty();
    }

    @Test
    void shouldKillAndRemoveAReviewThatAnotherWorkerLaunchedByItsName() {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 1);
        createJob(name, labels -> gone(withJob(labels, jobId)));
        String child = startChild(name);
        var handle = new SandboxBackend.Handle(name);

        backend().kill(handle);
        assertThat(job(name)).isNull();
        assertThat(storedPod(child).getSpec().getContainers())
                .as("the Pod keeps its spec: the worker's Role grants no Pod patch")
                .extracting(Container::getImage)
                .contains(IMAGE);

        backend().remove(handle);
        assertThat(job(name)).isNull();
        assertDeletedOnlyByItsJob(name);
    }

    @Test
    void shouldDeleteTheJobSoThatNoPodStartsWhenAnotherWorkerKillsAReviewBeforeItsPod() {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 1);
        createJob(name, labels -> gone(withJob(labels, jobId)));

        backend().kill(new SandboxBackend.Handle(name));

        assertThat(job(name)).isNull();
        assertDeletedOnlyByItsJob(name);
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
                        gateway -> Map.of(),
                        false));

        long seconds =
                storedJob(KubernetesSandboxBackend.jobName(jobId, 1)).getSpec().getActiveDeadlineSeconds();
        long expected = Duration.ofMinutes(7)
                .plus(KubernetesSandboxBackend.DEADLINE_BACKSTOP)
                .toSeconds();
        assertThat(seconds).isBetween(expected - 5, expected);
    }

    @Test
    void shouldListTheToolsJobsOfEveryWorker() {
        createToolsJob("hephaestus-tools-here", labels -> labels);
        createToolsJob("hephaestus-tools-of-gone-worker", KubernetesSandboxRuntimeTest::gone);

        assertThat(toolsPods(worker, pods).list())
                .extracting(ToolsPods.Listed::name)
                .containsExactlyInAnyOrder("hephaestus-tools-here", "hephaestus-tools-of-gone-worker");
    }

    private KubernetesToolsPods toolsPods(KubernetesWorker owner, KubernetesPods manifests) {
        return new KubernetesToolsPods(
                manifests,
                new KubernetesSandboxJobs(api(), owner),
                IMAGE,
                new SandboxExits(manifests, new SimpleMeterRegistry()),
                mock(KubernetesSandboxCache.class),
                Clock.systemUTC());
    }

    private Pod toolsPod(Map<String, String> labels) {
        return pods.toolsPod(new KubernetesPods.Workload("tools", labels, IMAGE, "tools"), LIMITS, "nonce", Map.of());
    }

    private void createToolsJob(String name, Function<Map<String, String>, Map<String, String>> labels) {
        Map<String, String> written = labels.apply(worker.labels(KubernetesWorker.KIND_TOOLS));
        Pod template = pods.toolsPod(new KubernetesPods.Workload(name, written, IMAGE, "tools"), LIMITS, "n", Map.of());
        api().batch()
                .v1()
                .jobs()
                .inNamespace(SANDBOXES)
                .resource(reviewJobs.job(template, 60))
                .create();
    }

    /** The labels as a worker process that no longer beats in the registry wrote them. */
    private static Map<String, String> gone(Map<String, String> labels) {
        labels.put(KubernetesWorker.WORKER_ID, "worker-process-gone");
        return labels;
    }

    static KubernetesIsolationProbe.Proof passed() {
        return new KubernetesIsolationProbe.Proof(Instant.now(), List.of(), true, "gvisor", 256);
    }

    // --- fixtures ---

    private KubernetesSandboxBackend backend() {
        return new KubernetesSandboxBackend(pods, reviewJobs, worker, new SandboxExits(pods, meters), cache());
    }

    /** The container's log, which the CRUD mock does not serve, for one read. */
    private void serveLogOnce(String pod, String container, String log) {
        Objects.requireNonNull(server)
                .expect()
                .get()
                .withPath(
                        "/api/v1/namespaces/" + SANDBOXES + "/pods/" + pod + "/log?pretty=false&container=" + container)
                .andReturn(200, log)
                .once();
    }

    private double exits(String reason) {
        return meters.get(AgentMetrics.SANDBOX_EXITS)
                .tag("kind", "review")
                .tag("reason", reason)
                .counter()
                .count();
    }

    /**
     * What the chart's Role allows: the worker deleted the Job with its Pod ({@code Foreground}), and never patched or
     * deleted a Pod itself.
     */
    private void assertDeletedOnlyByItsJob(String job) {
        KubernetesMockServer api = Objects.requireNonNull(server);
        List<RecordedRequest> received = new ArrayList<>();
        try {
            for (RecordedRequest request = api.takeRequest(10, TimeUnit.MILLISECONDS);
                    request != null;
                    request = api.takeRequest(10, TimeUnit.MILLISECONDS)) {
                received.add(request);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while reading the mock's requests", e);
        }
        assertThat(received)
                .filteredOn(request -> request.getPath().startsWith("/api/v1/namespaces/" + SANDBOXES + "/pods")
                        && List.of("PATCH", "DELETE").contains(request.getMethod()))
                .as("the worker's Role grants no Pod patch or delete")
                .isEmpty();
        assertThat(received)
                .filteredOn(request -> "DELETE".equals(request.getMethod())
                        && request.getPath().equals("/apis/batch/v1/namespaces/" + SANDBOXES + "/jobs/" + job))
                .as("the Job goes with its Pod, so that it starts no other")
                .isNotEmpty()
                .allSatisfy(request -> assertThat(api().getKubernetesSerialization()
                                .unmarshal(request.getUtf8Body(), DeleteOptions.class)
                                .getPropagationPolicy())
                        .isEqualTo("Foreground"));
    }

    private SandboxAttemptRunner adapter() {
        return new SandboxAttemptRunner(
                backend(),
                new SandboxWorkspaceManager(Path.of(System.getProperty("java.io.tmpdir"))),
                sessions,
                meters);
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
                KubernetesPods.REVIEW,
                null);
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
