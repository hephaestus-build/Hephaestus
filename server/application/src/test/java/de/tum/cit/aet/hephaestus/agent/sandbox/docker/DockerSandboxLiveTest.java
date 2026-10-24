package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxAttemptRunner;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.testconfig.LiveDockerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for the Docker sandbox manager using a real Docker daemon.
 *
 * <p>These tests require Docker to be available on the machine. They are excluded from the default
 * test suite (tagged {@code "live"}) and run with {@code -Dgroups=live}.
 *
 * <p>Each test creates real containers that are cleaned up in {@code @AfterEach}.
 */
@LiveDockerTest
@Tag("live")
class DockerSandboxLiveTest {

    private static final String AGENT_IMAGE =
            System.getenv().getOrDefault("HEPHAESTUS_AGENT_IMAGE_REFERENCE", "ghcr.io/hephaestus-build/agent-pi:dev");

    /** The transcript manifest that the agent image's gateway runner adds to every result it uploads. */
    private static final String TRACE_MANIFEST = "traces/manifest.json";

    /** The runner's cap on a result's transcripts: {@code TRACE_BYTES} in {@code docker/agents/pi/gateway-run.ts}. */
    private static final long TRACE_BYTES = 7L * 1024 * 1024;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final String owner = "live-" + UUID.randomUUID();
    private LiveSandboxGateway gateway;
    private SandboxAttemptRunner sandboxAdapter;
    private SandboxContainerManager containerManager;
    private SandboxNetworkManager networkManager;
    private SandboxWorkspaceManager workspaceManager;
    private DockerClientOperations dockerOps;
    private ContainerSecurityPolicy securityPolicy;
    private ExecutorService dockerWaitExecutor;

    @BeforeAll
    static void checkDocker() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping integration tests");
    }

    @BeforeEach
    void setUp() throws Exception {
        gateway = new LiveSandboxGateway();
        SandboxProperties properties = new SandboxProperties(5, 10, 60, null);
        var dockerProperties = new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, owner);

        // Wrapped exactly as DockerSandboxConfiguration wraps it, so the archive tests below exercise the
        // real Apache transport this application ships rather than docker-java's default ownership.
        var dockerClient = DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
                new ResponseOwnedDockerHttpClient(new ApacheDockerHttpClient.Builder()
                        .dockerHost(URI.create("unix:///var/run/docker.sock"))
                        .build()));

        dockerOps = new DockerClientOperations(dockerClient, dockerClient);
        dockerWaitExecutor = Executors.newCachedThreadPool();
        containerManager = new SandboxContainerManager(dockerOps, image -> {}, properties, owner, dockerWaitExecutor);
        networkManager = new SandboxNetworkManager(dockerOps, dockerProperties, new SandboxCreator(dockerOps));
        workspaceManager = new SandboxWorkspaceManager();
        securityPolicy = new ContainerSecurityPolicy(dockerProperties, null);

        var meters = new SimpleMeterRegistry();
        // The gateway runs in this process on the machine itself: the reviews get the route out to reach it.
        sandboxAdapter = new SandboxAttemptRunner(
                new DockerSandboxBackend(
                        networkManager,
                        containerManager,
                        securityPolicy,
                        dockerOps,
                        new SandboxCreator(dockerOps),
                        owner,
                        gateway::port,
                        true),
                workspaceManager,
                gateway.sessions(),
                meters);
    }

    @AfterEach
    void cleanupOrphanedResources() throws Exception {
        if (gateway != null) gateway.close();
        if (dockerWaitExecutor != null) {
            dockerWaitExecutor.shutdownNow();
        }
        // Safety net: remove any managed containers left over from failed tests
        try {
            containerManager.listManagedContainers().forEach(c -> {
                try {
                    containerManager.forceRemove(c.id());
                } catch (Exception ignored) {
                    // One resource that refuses to go must not stop the sweep of the rest.
                }
            });
        } catch (Exception ignored) {
            // The sweep is a safety net; Docker failing here must not mask the test's own result.
        }

        try {
            networkManager.listNetworks().forEach(n -> {
                try {
                    networkManager.removeNetwork(n.id());
                } catch (Exception ignored) {
                    // One resource that refuses to go must not stop the sweep of the rest.
                }
            });
        } catch (Exception ignored) {
            // The sweep is a safety net; Docker failing here must not mask the test's own result.
        }
    }

    /**
     * The runner's manifest of a result whose command started no model: its four fields exactly, a complete scan and no
     * session. The budget is what the result had left, up to the runner's cap, so only its bounds are fixed.
     */
    private static void requireEmptyTraceManifest(Map<String, byte[]> outputFiles) {
        JsonNode manifest = MAPPER.readTree(Objects.requireNonNull(outputFiles.get(TRACE_MANIFEST), TRACE_MANIFEST));
        assertThat(manifest.propertyNames())
                .containsExactlyInAnyOrder("schemaVersion", "budgetBytes", "sessionScanTruncated", "sessions");
        assertThat(manifest.path("schemaVersion").longValue()).isEqualTo(1);
        assertThat(manifest.path("budgetBytes").longValue()).isPositive().isLessThanOrEqualTo(TRACE_BYTES);
        assertThat(manifest.path("sessionScanTruncated").isBoolean()).isTrue();
        assertThat(manifest.path("sessionScanTruncated").booleanValue()).isFalse();
        assertThat(manifest.path("sessions").isArray()).isTrue();
        assertThat(manifest.path("sessions").size())
                .as("no session: the command started no model")
                .isZero();
    }

    @Nested
    class EndToEnd {

        @Test
        void shouldHandleNonZeroExit() {
            UUID jobId = UUID.randomUUID();

            SandboxSpec spec = new SandboxSpec(
                    jobId,
                    null,
                    AGENT_IMAGE,
                    List.of("sh", "-c", "exit 42"),
                    Map.of(),
                    "live-gateway-token",
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                    null,
                    Map.of(),
                    Map.of(),
                    List.of());

            SandboxResult result = sandboxAdapter.execute(spec);

            assertThat(result.exitCode()).isEqualTo(42);
            assertThat(result.timedOut()).isFalse();
        }
    }

    @Nested
    class OutputArchive {

        @Test
        void shouldCollectExactBytesWhenContainerWritesOutput() {
            String written = "{\"results\":[],\"schemaVersion\":1}\n";

            SandboxResult result = run("printf '%s' '" + written + "' > /workspace/out/result.json");

            assertThat(result.timedOut()).isFalse();
            assertThat(result.duration()).isPositive();
            assertThat(result.outputFiles()).containsOnlyKeys("result.json", TRACE_MANIFEST);
            assertThat(result.outputFiles().get("result.json")).isEqualTo(written.getBytes(StandardCharsets.UTF_8));
            requireEmptyTraceManifest(result.outputFiles());
        }

        private SandboxResult run(String script) {
            SandboxSpec spec = new SandboxSpec(
                    UUID.randomUUID(),
                    null,
                    AGENT_IMAGE,
                    List.of("sh", "-c", "mkdir -p /workspace/out && " + script),
                    Map.of(),
                    "live-gateway-token",
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                    null,
                    Map.of(),
                    Map.of(),
                    List.of());
            SandboxResult result = sandboxAdapter.execute(spec);
            assertThat(result.exitCode()).isZero();
            return result;
        }
    }

    @Nested
    class TimeoutEnforcement {

        @Test
        void shouldKillAfterTimeout() {
            UUID jobId = UUID.randomUUID();

            SandboxSpec spec = new SandboxSpec(
                    jobId,
                    null,
                    AGENT_IMAGE,
                    List.of("sh", "-c", "printf partial > /workspace/out/partial.txt; sleep 300"),
                    Map.of(),
                    "live-gateway-token",
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofSeconds(20)),
                    null,
                    Map.of(),
                    Map.of(),
                    List.of());

            SandboxResult result = sandboxAdapter.execute(spec);

            assertThat(result.timedOut()).isTrue();
            assertThat(result.exitCode()).isEqualTo(124);
            assertThat(result.outputFiles()).containsEntry("partial.txt", "partial".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    class FileInjection {

        @Test
        void shouldInjectFiles() {
            UUID jobId = UUID.randomUUID();

            SandboxSpec spec = new SandboxSpec(
                    jobId,
                    null,
                    AGENT_IMAGE,
                    List.of("sh", "-c", "mkdir -p /workspace/out && cat /workspace/.prompt > /workspace/out/echo.txt"),
                    Map.of(),
                    "live-gateway-token",
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                    null,
                    Map.of(".prompt", "injected content".getBytes(UTF_8)),
                    Map.of(),
                    List.of());

            SandboxResult result = sandboxAdapter.execute(spec);

            assertThat(result.exitCode()).isZero();
            assertThat(result.outputFiles()).containsKey("echo.txt");
            assertThat(new String(result.outputFiles().get("echo.txt"), UTF_8)).isEqualTo("injected content");
        }
    }

    @Nested
    class Cleanup {

        @Test
        void shouldCleanupAfterExecution() {
            UUID jobId = UUID.randomUUID();

            SandboxSpec spec = new SandboxSpec(
                    jobId,
                    null,
                    AGENT_IMAGE,
                    List.of("sh", "-c", "mkdir -p /workspace/out && echo cleanup-test > /workspace/out/done.txt"),
                    Map.of(),
                    "live-gateway-token",
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                    null,
                    Map.of(),
                    Map.of(),
                    List.of());

            sandboxAdapter.execute(spec);

            assertThat(containerManager.listManagedContainers().stream()
                            .filter(c -> jobId.toString().equals(c.labels().get("hephaestus.job-id")))
                            .toList())
                    .isEmpty();

            assertThat(networkManager.listNetworks().stream()
                            .filter(n -> n.name().contains(jobId.toString()))
                            .toList())
                    .isEmpty();
        }
    }
}
