package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.sun.net.httpserver.HttpServer;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Operation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.Request;
import de.tum.cit.aet.hephaestus.testconfig.LiveDockerTest;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;

/** The one place the trusted Git container is driven through the production Docker path. */
@Tag("live")
@LiveDockerTest
class DockerNativeGitExecutorLiveTest {
    private static final String IMAGE =
            System.getenv().getOrDefault("HEPHAESTUS_IMAGE_GIT_PREPARATION", "hephaestus-git-preparation:local");
    private static final RepositoryKey KEY = new RepositoryKey(9_000_001L, 9_000_001L);
    /** The same repository connected in a second workspace; one mirror per workspace. */
    private static final RepositoryKey OTHER_WORKSPACE = new RepositoryKey(9_000_002L, KEY.repositoryId());

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
        executor = executor(false);
    }

    private DockerNativeGitExecutor executor(boolean fetchFromWorkerNetwork) {
        var properties =
                new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, null, "live-git");
        var containers = new SandboxContainerManager(
                dockerOps, image -> {}, new SandboxProperties(5, 10, 60, null), "live-git", dockerWaitExecutor);
        // The test JVM runs on the host, which is where a fetch that joins the worker's network lands.
        var networks = new SandboxNetworkManager(dockerOps, properties, () -> null);
        return new DockerNativeGitExecutor(
                dockerOps,
                containers,
                networks,
                new ContainerSecurityPolicy(properties, null),
                new JsonMapper(),
                new DockerNativeGitExecutor.Settings(
                        IMAGE, "live-worker", 2, 1L << 33, "live-git", fetchFromWorkerNetwork));
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

    @Test
    void shouldFetchAPublicRepositoryAndKeepWorkspacesApart() {
        var fetch = new Request(Operation.FETCH, List.of(), "https://github.com/octocat/Hello-World.git", null);
        executor.execute(KEY, fetch, Duration.ofSeconds(120), OutputStream.nullOutputStream());

        assertThat(answer(KEY, new Request(Operation.STATUS, List.of(), null, null)))
                .isEqualTo("true");
        assertThat(answer(KEY, new Request(Operation.RESOLVE, List.of("refs/remotes/origin/master"), null, null)))
                .matches("[0-9a-f]{40}");
        assertThat(answer(OTHER_WORKSPACE, new Request(Operation.STATUS, List.of(), null, null)))
                .isEqualTo("false");
    }

    /**
     * An SCM simulation lives on the worker's loopback, which the Docker bridge cannot see. Git's dumb
     * HTTP transport needs only the files {@code update-server-info} writes, so a static server is a
     * complete simulated provider here.
     */
    @Test
    void shouldFetchAnScmSimulationFromTheWorkersOwnNetwork(@TempDir Path directory) throws Exception {
        Path work = directory.resolve("work");
        git(directory, "init", "-q", "-b", "main", work.toString());
        git(work, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "one");
        Path bare = directory.resolve("team/repo.git");
        git(directory, "clone", "-q", "--bare", work.toString(), bare.toString());
        git(bare, "update-server-info");
        String head = git(work, "rev-parse", "HEAD").strip();

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Path file = directory.resolve(exchange.getRequestURI().getPath().substring(1));
            if (!Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, Files.size(file));
            try (var body = exchange.getResponseBody()) {
                Files.copy(file, body);
            }
        });
        server.start();
        try {
            String cloneUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/team/repo.git";
            var fetch = new Request(Operation.FETCH, List.of(), cloneUrl, null);
            assertThatThrownBy(
                            () -> executor.execute(KEY, fetch, Duration.ofSeconds(60), OutputStream.nullOutputStream()))
                    .hasMessageContaining("Git operation failed: ");

            var simulation = executor(true);
            simulation.execute(KEY, fetch, Duration.ofSeconds(60), OutputStream.nullOutputStream());
            assertThat(answer(KEY, new Request(Operation.RESOLVE, List.of("refs/remotes/origin/main"), null, null)))
                    .isEqualTo(head);
        } finally {
            server.stop(0);
        }
    }

    private static String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0)
            throw new IllegalStateException("git " + String.join(" ", arguments) + ": " + output);
        return output;
    }

    private String answer(RepositoryKey key, Request request) {
        var output = new ByteArrayOutputStream();
        executor.execute(key, request, Duration.ofSeconds(60), output);
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}
