package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.runtime.PiPlanSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.ContainerSecurityPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerClientOperations;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxContainerManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxLabels;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxNetworkManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandboxState;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.EvictionReason;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.testconfig.LiveDockerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Live integration tests — boots real Docker. Run with {@code -Pgroups=live} or {@code live-tests}. */
@LiveDockerTest
@Tag("live")
class DockerInteractiveSandboxLiveTest {

    private static <T> T captured(AtomicReference<T> reference) {
        T value = reference.get();
        assertThat(value).isNotNull();
        return value;
    }

    /** The image under test. A release-channel tag would test some other release's image (ADR 0031);
     * point this at a locally built agent image, or export the reference a deployment would use. */
    private static final String AGENT_PI_IMAGE =
            System.getenv().getOrDefault("HEPHAESTUS_AGENT_IMAGE_REFERENCE", "ghcr.io/hephaestus-build/agent-pi:dev");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DockerClient dockerClient;
    private DockerClientOperations dockerOps;
    private SandboxNetworkManager networkManager;
    private SandboxWorkspaceManager workspaceManager;
    private SandboxContainerManager containerManager;
    private ContainerSecurityPolicy securityPolicy;
    private InteractiveSandboxRegistry registry;
    private InteractiveSandboxMetrics metrics;
    private StdinWriteWatchdog watchdog;
    private DockerInteractiveSandboxAdapter adapter;
    private ExecutorService dockerWaitExecutor;
    private SimpleMeterRegistry meterRegistry;
    private MentorProxyCredentialRegistry proxyCredentialRegistry;
    private byte[] runnerBytes;

    @BeforeAll
    static void checkDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available — skipping live tests");
    }

    @BeforeEach
    void setUp() throws Exception {
        SandboxProperties sandboxProperties = new SandboxProperties(5, 10, 60, 209_715_200L, 500_000, null);
        var dockerProperties =
                new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");
        // Tight TTL so idle eviction tests don't have to wait minutes.
        InteractiveSandboxProperties interactiveProperties = new InteractiveSandboxProperties(
                /* idleTtlSeconds */ 2,
                /* graceTimeoutSeconds */ 2,
                /* reapIntervalSeconds */ 1,
                /* ringBufferFrames */ 16,
                /* stdinWriteTimeoutMs */ 5_000,
                /* sendQueueCapacity */ 64,
                /* subscriberQueueCapacity */ 64,
                /* attachFirstFrameTimeoutSeconds */ 15,
                /* maxSessionsPerUser */ 3,
                /* maxSessionsTotal */ 50,
                /* maxFrameChars */ 64 * 1024);

        dockerClient = DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(URI.create("unix:///var/run/docker.sock"))
                        .build());

        dockerOps = new DockerClientOperations(dockerClient, dockerClient);
        dockerWaitExecutor = Executors.newCachedThreadPool();
        containerManager = new SandboxContainerManager(dockerOps, image -> {}, sandboxProperties, dockerWaitExecutor);
        networkManager = new SandboxNetworkManager(dockerOps, dockerProperties);
        workspaceManager = new SandboxWorkspaceManager(dockerOps);
        securityPolicy = new ContainerSecurityPolicy(dockerProperties, null);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new InteractiveSandboxMetrics(meterRegistry);
        watchdog = new StdinWriteWatchdog();
        registry = new InteractiveSandboxRegistry(
                interactiveProperties, containerManager, metrics, watchdog, meterRegistry);
        proxyCredentialRegistry = new MentorProxyCredentialRegistry();
        adapter = new DockerInteractiveSandboxAdapter(
                interactiveProperties,
                networkManager,
                workspaceManager,
                containerManager,
                securityPolicy,
                registry,
                metrics,
                MAPPER,
                dockerWaitExecutor,
                dockerProperties,
                8080,
                proxyCredentialRegistry);

        runnerBytes = Files.readAllBytes(Path.of("src/main/resources/agent/pi-mentor-runner.ts"));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (registry != null) {
            registry.shutdown();
        }
        // Belt-and-braces: clean up anything a failing test left behind.
        try {
            containerManager.listManagedContainers().forEach(c -> {
                try {
                    containerManager.forceRemove(c.id());
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        try {
            networkManager.listOrphanedNetworks().forEach(n -> {
                try {
                    networkManager.removeNetwork(n.id());
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        if (dockerWaitExecutor != null) {
            dockerWaitExecutor.shutdownNow();
        }
        if (dockerClient != null) {
            dockerClient.close();
        }
    }

    private InteractiveSandboxSpec buildSpec(String userId, String workspaceId) {
        UUID sessionId = UUID.randomUUID();
        SecurityProfile sec = new SecurityProfile(null, "private", List.of("ALL"), Map.of());
        return new InteractiveSandboxSpec(
                sessionId,
                userId,
                workspaceId,
                AGENT_PI_IMAGE,
                List.of("node", "/workspace/.runner/pi-mentor-runner.ts"),
                Map.of(),
                new NetworkPolicy(true, null, null),
                new ResourceLimits(512 * 1024 * 1024, 1.0, 256, Duration.ofMinutes(5)),
                sec,
                Map.of(".runner/pi-mentor-runner.ts", runnerBytes),
                Map.of());
    }

    private InteractiveSandboxSpec buildSpecWithProxyRoute(
            String userId, String workspaceId, String baseUrl, AtomicReference<String> token) {
        InteractiveSandboxSpec base = buildMentorSpec(userId, workspaceId);
        String minted = proxyCredentialRegistry.mint(
                base.sessionId(),
                new MentorProxyCredentialRegistry.Route("openai-completions", baseUrl, null, null, null, null));
        token.set(minted);
        return new InteractiveSandboxSpec(
                base.sessionId(),
                base.userId(),
                base.workspaceId(),
                base.image(),
                base.command(),
                base.environment(),
                new NetworkPolicy(true, null, minted),
                base.resourceLimits(),
                base.securityProfile(),
                base.inputFiles(),
                base.volumeMounts());
    }

    private static InteractiveSandboxSpec withContextSnapshot(InteractiveSandboxSpec base, String snapshot) {
        Map<String, byte[]> inputs = new HashMap<>(base.inputFiles());
        inputs.put("inputs/context/current_thread_history.json", snapshot.getBytes(StandardCharsets.UTF_8));
        return new InteractiveSandboxSpec(
                base.sessionId(),
                base.userId(),
                base.workspaceId(),
                base.image(),
                base.command(),
                base.environment(),
                base.networkPolicy(),
                base.resourceLimits(),
                base.securityProfile(),
                inputs,
                base.volumeMounts());
    }

    private static JsonNode ping() {
        return MAPPER.createObjectNode().put("type", "ping");
    }

    private static JsonNode echo(String payload) {
        return ((ObjectNode) MAPPER.createObjectNode().put("type", "echo")).put("payload", payload);
    }

    private static JsonNode emit(int count, String tag) {
        return ((ObjectNode) MAPPER.createObjectNode().put("type", "emit").put("count", count)).put("tag", tag);
    }

    @Nested
    class Handshake {

        @Test
        void pingPong() {
            AttachedSandbox sb = adapter.attach(buildSpec("u1", "w1"));
            CopyOnWriteArrayList<JsonNode> frames = new CopyOnWriteArrayList<>();
            sb.subscribe(frames::add);
            sb.send(ping());

            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(frames.stream()
                                    .anyMatch(n -> "pong".equals(n.path("type").asString())))
                            .isTrue());
            sb.close(Duration.ofSeconds(2));
        }
    }

    @Nested
    class UnicodeSafety {

        // Built at runtime: the U+2028/U+2029 escapes are pre-lexer line terminators in Java.
        private static final String LINE_SEP = Character.toString(0x2028);
        private static final String PARA_SEP = Character.toString(0x2029);

        @Test
        @DisplayName("U+2028 / U+2029 / \\n / \\r inside JSON string values survive intact")
        void unicodeSurvives() {
            AttachedSandbox sb = adapter.attach(buildSpec("u2", "w2"));
            CopyOnWriteArrayList<JsonNode> frames = new CopyOnWriteArrayList<>();
            sb.subscribe(frames::add);
            String payload = "a" + LINE_SEP + "b" + PARA_SEP + "c\nd\re";
            sb.send(echo(payload));

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                JsonNode echoBack = frames.stream()
                        .filter(n -> "echo_back".equals(n.path("type").asString()))
                        .findFirst()
                        .orElse(null);
                assertThat(echoBack).isNotNull();
                assertThat(echoBack.path("payload").asString()).isEqualTo(payload);
            });
            // 3-byte UTF-8 sequences; catches encoding regressions.
            assertThat(LINE_SEP.getBytes(StandardCharsets.UTF_8)).hasSize(3);
            assertThat(PARA_SEP.getBytes(StandardCharsets.UTF_8)).hasSize(3);
            sb.close(Duration.ofSeconds(2));
        }
    }

    @Nested
    class RingBufferOverflow {

        @Test
        @DisplayName("flood drops EXACTLY (frames+ready - capacity) and preserves NEWEST")
        void ringBufferDropsOldest() {
            double before = metrics.ringBufferDropped.count();
            AttachedSandbox sb = adapter.attach(buildSpec("u3", "w3"));
            int burst = 64;
            int ringCapacity = 16;
            sb.send(emit(burst, "burst"));
            // 1 startup "ready" + burst ticks - ring capacity = drops.
            int expectedDrops = (1 + burst) - ringCapacity;

            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(metrics.ringBufferDropped.count() - before)
                            .isEqualTo((double) expectedDrops));

            CopyOnWriteArrayList<JsonNode> snapshot = new CopyOnWriteArrayList<>();
            sb.subscribe(snapshot::add);
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(snapshot.stream()
                                    .filter(n -> "tick".equals(n.path("type").asString()))
                                    .count())
                            .isEqualTo(ringCapacity));
            int oldestRetainedN = burst - ringCapacity;
            int newestN = burst - 1;
            assertThat(snapshot.stream()
                            .filter(n -> "tick".equals(n.path("type").asString()))
                            .map(n -> n.path("n").asInt()))
                    .as("subscriber must observe ticks (%d..%d)", oldestRetainedN, newestN)
                    .contains(oldestRetainedN)
                    .contains(newestN)
                    .doesNotContain(oldestRetainedN - 1);

            sb.close(Duration.ofSeconds(2));
        }
    }

    @Nested
    class IdleEviction {

        @Test
        void idleReaped() {
            double before = metrics.evictionsBy(EvictionReason.IDLE).count();
            AttachedSandbox sb = adapter.attach(buildSpec("u4", "w4"));
            assertThat(((DockerAttachedSandboxAdapter) sb).state()).isEqualTo(AttachedSandboxState.ATTACHED);

            // Poll reap() rather than sleep so the test exits as soon as TTL elapses.
            await().atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        registry.reap();
                        assertThat(((DockerAttachedSandboxAdapter) sb).state()).isEqualTo(AttachedSandboxState.CLOSED);
                    });
            assertThat(metrics.evictionsBy(EvictionReason.IDLE).count() - before)
                    .isEqualTo(1.0);
        }
    }

    @Nested
    class ConcurrentAttach {

        @Test
        @DisplayName("same (userId, workspaceId) returns the same AttachedSandbox instance")
        void shareSemantics() {
            AttachedSandbox a = adapter.attach(buildSpec("u5", "w5"));
            AttachedSandbox b = adapter.attach(buildSpec("u5", "w5"));
            assertThat(b).isSameAs(a);
            a.close(Duration.ofSeconds(2));
        }

        @Test
        void compatibleReuseRevokesTheUnusedFreshProxyToken() {
            AtomicReference<String> firstToken = new AtomicReference<>();
            AtomicReference<String> unusedToken = new AtomicReference<>();
            AttachedSandbox first = adapter.attach(withContextSnapshot(
                    buildSpecWithProxyRoute("u_reuse", "w_reuse", "https://gateway.example/v1", firstToken),
                    "first turn"));

            AttachedSandbox reused = adapter.attach(withContextSnapshot(
                    buildSpecWithProxyRoute("u_reuse", "w_reuse", "https://gateway.example/v1", unusedToken),
                    "second turn"));

            assertThat(reused).isSameAs(first);
            assertThat(proxyCredentialRegistry.validate(captured(firstToken))).isPresent();
            assertThat(proxyCredentialRegistry.validate(captured(unusedToken))).isEmpty();
            first.close(Duration.ofSeconds(2));
        }

        @Test
        void changedProxyRouteReplacesTheWarmSandbox() {
            AtomicReference<String> oldToken = new AtomicReference<>();
            AtomicReference<String> newToken = new AtomicReference<>();
            AttachedSandbox oldSandbox =
                    adapter.attach(buildSpecWithProxyRoute("u_route", "w_route", "https://old.example/v1", oldToken));

            AttachedSandbox replacement =
                    adapter.attach(buildSpecWithProxyRoute("u_route", "w_route", "https://new.example/v1", newToken));

            assertThat(replacement).isNotSameAs(oldSandbox);
            assertThat(((DockerAttachedSandboxAdapter) oldSandbox).state()).isEqualTo(AttachedSandboxState.CLOSED);
            assertThat(proxyCredentialRegistry.validate(captured(oldToken))).isEmpty();
            assertThat(proxyCredentialRegistry.validate(captured(newToken)))
                    .get()
                    .extracting(route -> route.baseUrl())
                    .isEqualTo("https://new.example/v1");
            replacement.close(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("parallel attach race: loser's container/network are reclaimed (no leak)")
        void duplicateBranchTearsDownLoser() throws Exception {
            // Both callers race past the fast-path findLive and both spawn containers; the registry
            // resolves the race via tryRegister. Pre-fix the loser leaked its container + network.
            int containersBefore = managedInteractiveCount();
            CyclicBarrier gate = new CyclicBarrier(2);
            AtomicReference<AttachedSandbox> r1 = new AtomicReference<>();
            AtomicReference<AttachedSandbox> r2 = new AtomicReference<>();
            AtomicReference<@Nullable Throwable> err = new AtomicReference<>();
            Thread t1 = Thread.ofVirtual().start(() -> {
                try {
                    gate.await();
                    r1.set(adapter.attach(buildSpec("u_race", "w_race")));
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            });
            Thread t2 = Thread.ofVirtual().start(() -> {
                try {
                    gate.await();
                    r2.set(adapter.attach(buildSpec("u_race", "w_race")));
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            });
            t1.join();
            t2.join();
            assertThat(err.get()).isNull();
            assertThat(captured(r1)).isSameAs(r2.get());

            // Reclaim of loser is fire-and-forget; wait for the close future then assert one container.
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(managedInteractiveCount() - containersBefore)
                            .isEqualTo(1));

            captured(r1).close(Duration.ofSeconds(2));
        }

        private int managedInteractiveCount() {
            return (int) containerManager.listManagedContainers().stream()
                    .filter(c ->
                            SandboxLabels.KIND_INTERACTIVE.equals(c.labels().get(SandboxLabels.KIND)))
                    .count();
        }
    }

    @Nested
    class ContainerLabels {

        @Test
        @DisplayName("interactive containers carry KIND=interactive + SESSION_ID")
        void labelsPresent() {
            AttachedSandbox sb = adapter.attach(buildSpec("u6", "w6"));
            String sessionId = sb.identity().sessionId().toString();
            var match = containerManager.listManagedContainers().stream()
                    .filter(c -> sessionId.equals(c.labels().get(SandboxLabels.SESSION_ID)))
                    .findFirst();
            assertThat(match).as("Container with our SESSION_ID label exists").isPresent();
            assertThat(match.get().labels().get(SandboxLabels.KIND)).isEqualTo(SandboxLabels.KIND_INTERACTIVE);
            assertThat(match.get().labels().get(SandboxLabels.MANAGED)).isEqualTo("true");
            sb.close(Duration.ofSeconds(2));
        }
    }

    @Nested
    class WorkspaceMounts {

        @Test
        @DisplayName("uid 1000 can write to scratch + context/user but not context/target")
        void mountPermissions() {
            AttachedSandbox sb = adapter.attach(buildSpec("u7", "w7"));
            String containerId = ((DockerAttachedSandboxAdapter) sb).containerId();
            ProcessBuilder pb = new ProcessBuilder(
                    "docker",
                    "exec",
                    "-u",
                    "1000:1000",
                    containerId,
                    "sh",
                    "-c",
                    "set -e; " + "touch /workspace/scratch/probe && rm /workspace/scratch/probe && echo scratch:rw; "
                            + "touch /workspace/context/user/probe && rm /workspace/context/user/probe && echo user:rw; "
                            + "if touch /workspace/context/target/probe 2>/dev/null; then echo target:RW_UNEXPECTED; else echo target:ro; fi; "
                            + "if touch /workspace/context/probe 2>/dev/null; then echo context:RW_UNEXPECTED; else echo context:ro; fi");
            pb.redirectErrorStream(true);
            try {
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                assertThat(out).contains("scratch:rw");
                assertThat(out).contains("user:rw");
                assertThat(out).contains("target:ro");
                assertThat(out).contains("context:ro");
                assertThat(out).doesNotContain("RW_UNEXPECTED");
            } catch (Exception e) {
                throw new AssertionError("Failed to probe mount permissions: " + e.getMessage(), e);
            } finally {
                sb.close(Duration.ofSeconds(2));
            }
        }

        @Test
        void agentPiWorkspaceSetupWithCapDropAll() {
            // agent-pi pre-creates /workspace owned by 1000:1000, so mkdir as root under --cap-drop=ALL
            // (no CAP_DAC_OVERRIDE) fails without the CONTAINER_USER fix.
            assumeTrue(
                    dockerOps.imageIsPresent(AGENT_PI_IMAGE),
                    "agent-pi image not in local daemon — build with: docker build -t " + AGENT_PI_IMAGE
                            + " docker/agents/pi/");
            UUID sessionId = UUID.randomUUID();
            SecurityProfile sec = new SecurityProfile(null, "private", List.of("ALL"), Map.of());
            InteractiveSandboxSpec piSpec = new InteractiveSandboxSpec(
                    sessionId,
                    "u_pi_perm",
                    "w_pi_perm",
                    AGENT_PI_IMAGE,
                    List.of("node", "/workspace/.runner/pi-mentor-runner.ts"),
                    Map.of(),
                    new NetworkPolicy(true, null, null),
                    new ResourceLimits(512 * 1024 * 1024, 1.0, 256, Duration.ofMinutes(5)),
                    sec,
                    Map.of(".runner/pi-mentor-runner.ts", runnerBytes),
                    Map.of());
            AttachedSandbox sb = adapter.attach(piSpec);
            assertThat(((DockerAttachedSandboxAdapter) sb).state()).isEqualTo(AttachedSandboxState.ATTACHED);
            sb.close(Duration.ofSeconds(2));
        }
    }

    @Nested
    class AttachFailureModes {

        @Test
        void runnerCrashesBeforeFirstFrame() {
            double timeoutBefore = metrics.attachFailureFirstFrameTimeout.count();
            double failedBefore = metrics.attachFailureFirstFrameFailed.count();
            // sh -c 'exit 1' instead of the JSONL runner: pump sees EOF before any frame.
            InteractiveSandboxSpec base = buildSpec("u_crash", "w_crash");
            InteractiveSandboxSpec brokenSpec = new InteractiveSandboxSpec(
                    base.sessionId(),
                    base.userId(),
                    base.workspaceId(),
                    base.image(),
                    List.of("sh", "-c", "exit 1"),
                    base.environment(),
                    base.networkPolicy(),
                    base.resourceLimits(),
                    base.securityProfile(),
                    base.inputFiles(),
                    base.volumeMounts());
            Assertions.assertThatThrownBy(() -> adapter.attach(brokenSpec))
                    .isInstanceOf(InteractiveSandboxException.class);
            // Must distinguish runner-crash from flow-control timeout for dashboards.
            assertThat(metrics.attachFailureFirstFrameFailed.count() - failedBefore)
                    .isEqualTo(1.0);
            assertThat(metrics.attachFailureFirstFrameTimeout.count() - timeoutBefore)
                    .isZero();
        }
    }

    /**
     * Exercises the production {@link PiRuntimeFactory} bootstrap with protocol-only SDK behavior,
     * without requiring an LLM or API key.
     */
    private InteractiveSandboxSpec buildMentorSpec(String userId, String workspaceId) {
        PiRuntimeFactory factory = new PiRuntimeFactory(MAPPER);
        PiPlanSpec planSpec = new PiPlanSpec(
                "openai-completions",
                "stub-model",
                null,
                null,
                false,
                "stub-proxy-token",
                true,
                120,
                new MentorRunnerProfile(),
                Map.of(),
                "");
        PiRuntimeFactory.PiPlan plan = factory.build(planSpec);

        Map<String, String> env = new HashMap<>(plan.environment());
        // Stub: skip Pi SDK load; runner emits runner_ready + handles requests without an LLM.
        env.put("MENTOR_RUNNER_PROTOCOL_ONLY", "1");

        return new InteractiveSandboxSpec(
                UUID.randomUUID(),
                userId,
                workspaceId,
                AGENT_PI_IMAGE,
                plan.command(),
                Map.copyOf(env),
                plan.networkPolicy(),
                ResourceLimits.DEFAULT,
                SecurityProfile.DEFAULT,
                plan.inputFiles(),
                Map.of());
    }

    @Nested
    class MentorRpcProtocol {

        private static final Duration RPC_TIMEOUT = Duration.ofSeconds(25);

        @Test
        void productionBootstrapSucceeds() {
            assumeTrue(
                    dockerOps.imageIsPresent(AGENT_PI_IMAGE),
                    "agent-pi image not in local daemon — build with: docker build -t " + AGENT_PI_IMAGE
                            + " docker/agents/pi/");
            // A failed bootstrap step means the pump sees EOF with a non-zero exit and attach() throws.
            AttachedSandbox sb = adapter.attach(buildMentorSpec("u_boot", "w_boot"));
            CopyOnWriteArrayList<JsonNode> frames = new CopyOnWriteArrayList<>();
            sb.subscribe(frames::add);
            await().atMost(RPC_TIMEOUT)
                    .untilAsserted(() -> assertThat(frames.stream()
                                    .anyMatch(f -> "runner_ready"
                                            .equals(f.path("params")
                                                    .path("event")
                                                    .path("type")
                                                    .asString())))
                            .isTrue());
            sb.close(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("hello → {protocolVersion:1}")
        void helloHandshake() {
            assumeTrue(dockerOps.imageIsPresent(AGENT_PI_IMAGE), "agent-pi image not in local daemon");
            AttachedSandbox sb = adapter.attach(buildMentorSpec("u_hello", "w_hello"));
            CopyOnWriteArrayList<JsonNode> frames = new CopyOnWriteArrayList<>();
            sb.subscribe(frames::add);

            await().atMost(RPC_TIMEOUT)
                    .untilAsserted(() -> assertThat(frames.stream()
                                    .anyMatch(f -> "runner_ready"
                                            .equals(f.path("params")
                                                    .path("event")
                                                    .path("type")
                                                    .asString())))
                            .isTrue());

            String helloId = UUID.randomUUID().toString();
            sb.send(MAPPER.createObjectNode()
                    .<ObjectNode>put("jsonrpc", "2.0")
                    .<ObjectNode>put("id", helloId)
                    .<ObjectNode>put("method", "hello")
                    .set("params", MAPPER.createObjectNode()));

            await().atMost(RPC_TIMEOUT).untilAsserted(() -> {
                JsonNode resp = frames.stream()
                        .filter(f -> helloId.equals(f.path("id").asString()))
                        .findFirst()
                        .orElse(null);
                assertThat(resp).as("hello response").isNotNull();
                assertThat(resp.path("result").path("protocolVersion").asInt())
                        .as("protocolVersion")
                        .isEqualTo(1);
            });
            sb.close(Duration.ofSeconds(2));
        }

        @Test
        void stubTurn() {
            assumeTrue(dockerOps.imageIsPresent(AGENT_PI_IMAGE), "agent-pi image not in local daemon");
            AttachedSandbox sb = adapter.attach(buildMentorSpec("u_turn", "w_turn"));
            CopyOnWriteArrayList<JsonNode> frames = new CopyOnWriteArrayList<>();
            sb.subscribe(frames::add);

            await().atMost(RPC_TIMEOUT)
                    .untilAsserted(() -> assertThat(frames.stream()
                                    .anyMatch(f -> "runner_ready"
                                            .equals(f.path("params")
                                                    .path("event")
                                                    .path("type")
                                                    .asString())))
                            .isTrue());

            String helloId = UUID.randomUUID().toString();
            sb.send(MAPPER.createObjectNode()
                    .<ObjectNode>put("jsonrpc", "2.0")
                    .<ObjectNode>put("id", helloId)
                    .<ObjectNode>put("method", "hello")
                    .set("params", MAPPER.createObjectNode()));
            await().atMost(RPC_TIMEOUT)
                    .untilAsserted(() -> assertThat(frames.stream()
                                    .anyMatch(f -> helloId.equals(f.path("id").asString())))
                            .isTrue());

            String threadId = UUID.randomUUID().toString();
            String openId = UUID.randomUUID().toString();
            sb.send(MAPPER.createObjectNode()
                    .<ObjectNode>put("jsonrpc", "2.0")
                    .<ObjectNode>put("id", openId)
                    .<ObjectNode>put("method", "open_thread")
                    .set("params", MAPPER.createObjectNode().put("threadId", threadId)));
            await().atMost(RPC_TIMEOUT).untilAsserted(() -> {
                JsonNode openResp = frames.stream()
                        .filter(f -> openId.equals(f.path("id").asString()))
                        .findFirst()
                        .orElse(null);
                assertThat(openResp).as("open_thread response").isNotNull();
                assertThat(openResp.path("result").path("threadId").asString()).isEqualTo(threadId);
            });

            String promptId = UUID.randomUUID().toString();
            sb.send(MAPPER.createObjectNode()
                    .<ObjectNode>put("jsonrpc", "2.0")
                    .<ObjectNode>put("id", promptId)
                    .<ObjectNode>put("method", "prompt")
                    .set(
                            "params",
                            MAPPER.createObjectNode().put("threadId", threadId).put("text", "Hello, stub!")));
            await().atMost(RPC_TIMEOUT).untilAsserted(() -> {
                JsonNode promptResp = frames.stream()
                        .filter(f -> promptId.equals(f.path("id").asString()))
                        .findFirst()
                        .orElse(null);
                assertThat(promptResp).as("prompt response").isNotNull();
                assertThat(promptResp.path("result").path("accepted").asBoolean())
                        .isTrue();
            });

            // The stub emits agent_start → message_update → agent_end scoped to threadId.
            await().atMost(RPC_TIMEOUT).untilAsserted(() -> {
                List<JsonNode> events = frames.stream()
                        .filter(f -> "event".equals(f.path("method").asString())
                                && threadId.equals(
                                        f.path("params").path("threadId").asString()))
                        .map(f -> f.path("params").path("event"))
                        .collect(Collectors.toList());
                assertThat(events.stream()
                                .anyMatch(
                                        e -> "agent_start".equals(e.path("type").asString())))
                        .as("agent_start")
                        .isTrue();
                assertThat(events.stream()
                                .anyMatch(e ->
                                        "message_update".equals(e.path("type").asString())))
                        .as("message_update / text_delta")
                        .isTrue();
                assertThat(events.stream()
                                .anyMatch(e -> "agent_end".equals(e.path("type").asString())))
                        .as("agent_end")
                        .isTrue();
            });
            sb.close(Duration.ofSeconds(2));
        }
    }

    @Nested
    class CloseLifecycle {

        @Test
        void closeRemoves() {
            AttachedSandbox sb = adapter.attach(buildSpec("u8", "w8"));
            String containerId = ((DockerAttachedSandboxAdapter) sb).containerId();
            sb.close(Duration.ofSeconds(2));
            assertThat(((DockerAttachedSandboxAdapter) sb).state()).isEqualTo(AttachedSandboxState.CLOSED);
            assertThat(containerManager.listManagedContainers().stream()
                            .filter(c -> containerId.equals(c.id()))
                            .toList())
                    .isEmpty();
        }

        @Test
        void sendAfterClose() {
            AttachedSandbox sb = adapter.attach(buildSpec("u9", "w9"));
            sb.close(Duration.ofSeconds(2));
            Assertions.assertThatThrownBy(() -> sb.send(ping())).isInstanceOf(InteractiveSandboxException.class);
        }
    }
}
