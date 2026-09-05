package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.testconfig.LiveDockerTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

/**
 * Proves against a real Docker daemon that content staged by path arrives in the sandbox intact, at
 * sizes no in-memory staging path could carry.
 *
 * <p>The unit tests assert what goes into the tar. This asserts what the agent can actually read,
 * which is the only claim that matters to a review: a practice cannot judge evidence the container
 * never received.
 */
@LiveDockerTest
@Tag("live")
class RepositoryTreeStagingLiveTest {

    /** One blob far past anything a heap-bound stager could hold. */
    private static final int LARGE_FILE_MB = 64;

    /** The image under test. A release-channel tag would test some other release's image (ADR 0031);
     * point this at a locally built agent image, or export the reference a deployment would use. */
    private static final String AGENT_IMAGE =
            System.getenv().getOrDefault("HEPHAESTUS_AGENT_IMAGE_REFERENCE", "ghcr.io/hephaestus-build/agent-pi:dev");

    /** A tree large enough that any per-file ceiling would have to reject it. */
    private static final int TREE_FILE_COUNT = 25_000;

    private DockerSandboxAdapter sandboxAdapter;
    private SandboxContainerManager containerManager;
    private SandboxNetworkManager networkManager;
    private ExecutorService dockerWaitExecutor;

    @BeforeAll
    static void checkDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        assumeTrue(
                DockerClientFactory.lazyClient().listImagesCmd().exec().stream()
                        .anyMatch(image -> image.getRepoTags() != null
                                && java.util.Arrays.asList(image.getRepoTags()).contains(AGENT_IMAGE)),
                AGENT_IMAGE + " not present locally");
    }

    @BeforeEach
    void setUp() {
        SandboxProperties properties = new SandboxProperties(5, 10, 300, 209_715_200L, 500_000, null);
        var dockerProperties =
                new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "docker");
        var dockerClient = DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(URI.create("unix:///var/run/docker.sock"))
                        .build());
        DockerClientOperations dockerOps = new DockerClientOperations(dockerClient, dockerClient);
        dockerWaitExecutor = Executors.newCachedThreadPool();
        containerManager = new SandboxContainerManager(dockerOps, image -> {}, properties, dockerWaitExecutor);
        networkManager = new SandboxNetworkManager(dockerOps, dockerProperties);
        sandboxAdapter = new DockerSandboxAdapter(
                networkManager,
                new SandboxWorkspaceManager(dockerOps),
                containerManager,
                new ContainerSecurityPolicy(dockerProperties, null),
                8080,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void cleanup() {
        if (dockerWaitExecutor != null) {
            dockerWaitExecutor.shutdownNow();
        }
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

    @Test
    @DisplayName("a 64 MB file and a 25,000-file tree both arrive whole in the sandbox")
    void shouldStageATreeFarPastEveryRemovedLimit(@TempDir Path staging) throws Exception {
        Map<String, Path> onDisk = new LinkedHashMap<>();

        Path large = staging.resolve("large.bin");
        byte[] chunk = new byte[1024 * 1024];
        java.util.Arrays.fill(chunk, (byte) 'x');
        try (OutputStream out = Files.newOutputStream(large)) {
            for (int i = 0; i < LARGE_FILE_MB; i++) {
                out.write(chunk);
            }
        }
        onDisk.put(SandboxLayout.REPO_MOUNT_RELATIVE + "large.bin", large);

        Path many = Files.createDirectories(staging.resolve("many"));
        for (int i = 0; i < TREE_FILE_COUNT; i++) {
            Path file = many.resolve("file" + i + ".txt");
            Files.writeString(file, "content " + i);
            onDisk.put(SandboxLayout.REPO_MOUNT_RELATIVE + "many/file" + i + ".txt", file);
        }

        String script = "set -e\n" + "repo=/workspace/"
                + SandboxLayout.REPO_MOUNT_RELATIVE
                + "\n"
                + "bytes=$(wc -c < ${repo}large.bin)\n"
                + "files=$(find ${repo}many -type f | wc -l)\n"
                + "sample=$(cat ${repo}many/file24999.txt)\n"
                + "echo \"bytes=$bytes files=$files sample=$sample\" > /workspace/"
                + SandboxLayout.ANALYSIS_PREFIX
                + "seen.txt\n";

        SandboxSpec spec = new SandboxSpec(
                UUID.randomUUID(),
                AGENT_IMAGE,
                List.of("sh", "-c", script),
                Map.of(),
                new NetworkPolicy(true, null, null),
                new ResourceLimits(512L * 1024 * 1024, 1.0, 128, Duration.ofMinutes(5)),
                new SecurityProfile(null, "private", List.of("ALL"), Map.of()),
                // A staged work/ file makes the writable region exist as uid 1000, exactly as a real run does;
                // /workspace itself stays root-owned, which is why the script cannot write outside work/.
                Map.of(SandboxLayout.ANALYSIS_PREFIX + ".gitkeep", new byte[0]),
                onDisk,
                "/workspace/" + SandboxLayout.ANALYSIS_PREFIX,
                null);

        SandboxResult result = sandboxAdapter.execute(spec);

        assertThat(result.exitCode()).as("container logs: %s", result.logs()).isZero();
        String seen = new String(result.outputFiles().get("seen.txt")).trim();
        assertThat(seen)
                .isEqualTo("bytes=" + (LARGE_FILE_MB * 1024L * 1024L) + " files=" + TREE_FILE_COUNT
                        + " sample=content 24999");
    }
}
