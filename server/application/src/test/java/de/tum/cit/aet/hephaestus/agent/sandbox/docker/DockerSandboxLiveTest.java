package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.testconfig.LiveDockerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

/** Runs sandbox lifecycle tests against a real Docker daemon. */
@LiveDockerTest
@Tag("live")
class DockerSandboxLiveTest {

    private DockerSandboxAdapter sandboxAdapter;
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
    void setUp() {
        SandboxProperties properties = new SandboxProperties(5, 10, 60, 209_715_200L, 500_000, null);
        var dockerProperties =
                new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");

        // Wrapped exactly as DockerSandboxConfiguration wraps it, so the archive tests below exercise the
        // real Apache transport this application ships rather than docker-java's default ownership.
        var dockerClient = DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
                new ResponseOwnedDockerHttpClient(new ApacheDockerHttpClient.Builder()
                        .dockerHost(URI.create("unix:///var/run/docker.sock"))
                        .build()));

        dockerOps = new DockerClientOperations(dockerClient, dockerClient);
        dockerWaitExecutor = Executors.newCachedThreadPool();
        containerManager = new SandboxContainerManager(dockerOps, image -> {}, properties, dockerWaitExecutor);
        networkManager = new SandboxNetworkManager(dockerOps, dockerProperties);
        workspaceManager = new SandboxWorkspaceManager(dockerOps);
        securityPolicy = new ContainerSecurityPolicy(dockerProperties, null);

        sandboxAdapter = new DockerSandboxAdapter(
                networkManager, workspaceManager, containerManager, securityPolicy, 8080, new SimpleMeterRegistry());
    }

    @AfterEach
    void cleanupOrphanedResources() {
        if (dockerWaitExecutor != null) {
            dockerWaitExecutor.shutdownNow();
        }
        // Safety net: remove any managed containers left over from failed tests
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
    }

    private SecurityProfile testSecurityProfile() {
        return new SecurityProfile(null, "private", List.of("ALL"), Map.of());
    }

    @Nested
    class EndToEnd {

        @Test
        void shouldHandleNonZeroExit() {
            UUID jobId = UUID.randomUUID();

            SandboxSpec spec = new SandboxSpec(
                    jobId,
                    "alpine:latest",
                    List.of("sh", "-c", "exit 42"),
                    Map.of(),
                    new NetworkPolicy(true, null, null),
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                    testSecurityProfile(),
                    Map.of(),
                    "/workspace/out",
                    null);

            SandboxResult result = sandboxAdapter.execute(spec);

            assertThat(result.exitCode()).isEqualTo(42);
            assertThat(result.timedOut()).isFalse();
        }
    }

    /**
     * The archive the reader parses is produced by the daemon's own Go {@code archive/tar}, so these are
     * the cases a hand-built fixture cannot vouch for: what Moby actually emits, and what it emits for a
     * name or an entry the reader refuses.
     */
    @Nested
    class OutputArchive {

        @Test
        void shouldCollectExactBytesWhenContainerWritesOutput() {
            String written = "{\"observations\":[],\"schemaVersion\":1}\n";

            SandboxResult result = run("printf '%s' '" + written + "' > /var/tmp/out/result.json");

            assertThat(result.timedOut()).isFalse();
            assertThat(result.duration()).isPositive();
            assertThat(result.outputFiles()).containsOnlyKeys("result.json");
            assertThat(result.outputFiles().get("result.json")).isEqualTo(written.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void shouldRejectRunWhenAnOutputNameNeedsANameExtensionRecord() {
            String name = "a".repeat(97) + ".json";

            assertThatThrownBy(() -> run("echo '{}' > /var/tmp/out/" + name))
                    .isInstanceOf(SandboxException.class)
                    .cause()
                    .hasMessageContaining("at most 100 ASCII bytes");
        }

        @Test
        void shouldRejectRunWhenOutputContainsASymlink() {
            assertThatThrownBy(() -> run("ln -s /etc/passwd /var/tmp/out/leak"))
                    .isInstanceOf(SandboxException.class)
                    .cause()
                    .hasMessageContaining("regular files");
        }

        @Test
        void shouldRejectRunWhenOutputExceedsTheBudget() {
            var tightAdapter = new DockerSandboxAdapter(
                    networkManager,
                    new SandboxWorkspaceManager(dockerOps, 4096, 4096, 4096, 16),
                    containerManager,
                    securityPolicy,
                    8080,
                    new SimpleMeterRegistry());

            assertThatThrownBy(() -> run("dd if=/dev/zero of=/var/tmp/out/big.bin bs=1k count=64", tightAdapter))
                    .isInstanceOf(SandboxException.class)
                    .cause()
                    .hasMessageContaining("extracted size limit");
        }

        private SandboxResult run(String script) {
            return run(script, sandboxAdapter);
        }

        /**
         * Collects from {@code /var/var/tmp/out} rather than {@code /workspace/out}: the container runs as uid
         * 1000, a stock image has no {@code /workspace} it may create, and the policy's mandatory tmpfs
         * mounts are gone by the time the archive is read. Only the {@code out} basename reaches the
         * reader, so the archive is shaped exactly as production's.
         */
        private SandboxResult run(String script, DockerSandboxAdapter adapter) {
            SandboxSpec spec = new SandboxSpec(
                    UUID.randomUUID(),
                    "alpine:latest",
                    List.of("sh", "-c", "mkdir -p /var/tmp/out && " + script),
                    Map.of(),
                    new NetworkPolicy(true, null, null),
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                    testSecurityProfile(),
                    Map.of(),
                    "/var/tmp/out",
                    null);
            SandboxResult result = adapter.execute(spec);
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
                    "alpine:latest",
                    List.of("sh", "-c", "sleep 300"),
                    Map.of(),
                    new NetworkPolicy(true, null, null),
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofSeconds(3)),
                    testSecurityProfile(),
                    Map.of(),
                    "/workspace/out",
                    null);

            SandboxResult result = sandboxAdapter.execute(spec);

            assertThat(result.timedOut()).isTrue();
            // Exit code should be 137 (SIGKILL) or 143 (SIGTERM)
            assertThat(result.exitCode()).isIn(137, 143);
        }
    }

    @Nested
    class FileInjection {

        @Test
        void shouldInjectFiles() {
            UUID jobId = UUID.randomUUID();

            SandboxSpec spec = new SandboxSpec(
                    jobId,
                    "alpine:latest",
                    List.of("sh", "-c", "mkdir -p /workspace/out && cat /workspace/.prompt > /workspace/out/echo.txt"),
                    Map.of(),
                    new NetworkPolicy(true, null, null),
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                    testSecurityProfile(),
                    Map.of(".prompt", "injected content".getBytes()),
                    "/workspace/out",
                    null);

            SandboxResult result = sandboxAdapter.execute(spec);

            assertThat(result.exitCode()).isZero();
            assertThat(result.outputFiles()).containsKey("echo.txt");
            assertThat(new String(result.outputFiles().get("echo.txt"))).isEqualTo("injected content");
        }
    }

    @Nested
    class Cleanup {

        @Test
        void shouldCleanupAfterExecution() {
            UUID jobId = UUID.randomUUID();

            SandboxSpec spec = new SandboxSpec(
                    jobId,
                    "alpine:latest",
                    List.of("sh", "-c", "mkdir -p /var/tmp/out && echo cleanup-test > /var/tmp/out/done.txt"),
                    Map.of(),
                    new NetworkPolicy(true, null, null),
                    new ResourceLimits(256 * 1024 * 1024, 0.5, 64, Duration.ofMinutes(1)),
                    testSecurityProfile(),
                    Map.of(),
                    "/var/tmp/out",
                    null);

            sandboxAdapter.execute(spec);

            assertThat(containerManager.listManagedContainers().stream()
                            .filter(c -> jobId.toString().equals(c.labels().get("hephaestus.job-id")))
                            .toList())
                    .isEmpty();

            assertThat(networkManager.listOrphanedNetworks().stream()
                            .filter(n -> n.name().contains(jobId.toString()))
                            .toList())
                    .isEmpty();
        }
    }
}
