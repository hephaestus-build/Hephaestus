package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Operation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Request;
import de.tum.cit.aet.hephaestus.testconfig.LiveDockerTest;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;

/** The one place the trusted Git container is driven through the production Docker path. */
@Tag("live")
@LiveDockerTest
class DockerNativeGitExecutorLiveTest {
    private static final String IMAGE =
            System.getenv().getOrDefault("HEPHAESTUS_IMAGE_GIT_PREPARATION", "hephaestus-git-preparation:local");
    private static final RepositoryKey KEY = new RepositoryKey(9_000_001L, 9_000_001L);

    private DockerClientOperations dockerOps;
    private ExecutorService dockerWaitExecutor;
    private DockerNativeGitExecutor executor;

    @BeforeAll
    static void checkDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
    }

    @BeforeEach
    void setUp() {
        var dockerClient = DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
                new ResponseOwnedDockerHttpClient(new ApacheDockerHttpClient.Builder()
                        .dockerHost(URI.create("unix:///var/run/docker.sock"))
                        .build()));
        dockerOps = new DockerClientOperations(dockerClient, dockerClient);
        dockerWaitExecutor = Executors.newCachedThreadPool();
        var containers = new SandboxContainerManager(
                dockerOps, image -> {}, new SandboxProperties(5, 10, 60, null), "live-git", dockerWaitExecutor);
        executor = new DockerNativeGitExecutor(
                dockerOps,
                containers,
                image -> {},
                new ContainerSecurityPolicy(
                        new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "live-git"),
                        null),
                new JsonMapper(),
                new DockerNativeGitExecutor.Settings(IMAGE, "live-worker", 2, 1L << 33, "live-git", null));
    }

    @AfterEach
    void cleanup() {
        executor.deleteRepository(KEY.repositoryId());
        dockerWaitExecutor.shutdownNow();
    }

    @Test
    void shouldCarryTheHelpersDiagnosticWhenAnOperationFails() {
        var fetch = new Request(Operation.FETCH, List.of(), "https://127.0.0.1:9/team/repo.git", null);
        assertThatThrownBy(() -> executor.execute(KEY, fetch, Duration.ofSeconds(60), OutputStream.nullOutputStream()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Git operation failed: ")
                .hasMessageContaining("127.0.0.1");
    }

    @Test
    void shouldAnswerAStatusQueryThroughTheContainer() {
        var output = new ByteArrayOutputStream();
        executor.execute(KEY, new Request(Operation.STATUS, List.of(), null, null), Duration.ofSeconds(60), output);
        assertThat(output.toString(StandardCharsets.UTF_8).strip()).isEqualTo("false");
    }
}
