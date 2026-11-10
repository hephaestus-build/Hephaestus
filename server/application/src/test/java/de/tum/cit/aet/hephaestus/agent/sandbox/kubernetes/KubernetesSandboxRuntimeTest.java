package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

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
    void shouldBoundTheDiskOfEverySandboxPodSoThatNoneCanFillItsNode() {
        Map<String, String> review = Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_REVIEW);
        Pod toolsPod = toolsPod(Map.of());

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
    void shouldRankConversationsAboveEveryProbe() {
        Map<String, String> tools = Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TOOLS);
        Map<String, String> toolsProbe =
                Map.of(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_TOOLS, KubernetesWorker.PROBE, "run");

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldStartNoSandboxBeforeTheIsolationProbePassed(boolean probeRanWithoutUserNamespaces) {
        var unproved = new KubernetesWorker(properties, WORKER_ID, 8081);
        if (probeRanWithoutUserNamespaces) {
            unproved.proved(new KubernetesIsolationProbe.Proof(Instant.now(), List.of(), false, "gvisor", 256));
        }
        var unprovedPods = new KubernetesPods(api(), unproved, 10, ImagePullPolicy.IF_NOT_PRESENT);
        HealthIndicator readiness =
                new KubernetesSandboxConfiguration().kubernetesSandboxHealthIndicator(cache(), unproved);

        assertThat(Objects.requireNonNull(readiness.health()).getStatus())
                .as("the worker is not ready")
                .isEqualTo(Status.DOWN);
        assertThatThrownBy(() -> toolsPods(unproved, unprovedPods).create("tools", "nonce", LIMITS))
                .isInstanceOf(SandboxInfrastructureException.class);
        assertThat(api().batch().v1().jobs().inNamespace(SANDBOXES).list().getItems())
                .isEmpty();
        assertThat(api().pods().inNamespace(SANDBOXES).list().getItems()).isEmpty();

        unproved.proved(new KubernetesIsolationProbe.Proof(Instant.now(), List.of(), true, "", 256));

        assertThat(Objects.requireNonNull(readiness.health()).getStatus()).isEqualTo(Status.UP);
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

    private @Nullable Job job(String name) {
        return api().batch().v1().jobs().inNamespace(SANDBOXES).withName(name).get();
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

    private static ContainerStatus running(String name) {
        return new ContainerStatusBuilder()
                .withName(name)
                .withNewState()
                .withNewRunning()
                .endRunning()
                .endState()
                .build();
    }

    private static Container container(Pod pod, String name) {
        return pod.getSpec().getContainers().stream()
                .filter(container -> name.equals(container.getName()))
                .findFirst()
                .orElseThrow();
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
