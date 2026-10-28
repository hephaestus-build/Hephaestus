package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.ContainerSecurityPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerClientOperations;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxCreator;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.testconfig.LiveDockerTest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Heph's context folder in real turn sandboxes on the local Docker daemon, with the agent image named by
 * {@code HEPHAESTUS_AGENT_IMAGE_REFERENCE}: staging and replacing with exact bytes, {@code read}, {@code bash} with Git
 * on a loaded repository, the read-only folder, the timeout and its cancellation of a detached child, and the removal
 * of a sandbox while its command runs. It logs the time of each step.
 */
@LiveDockerTest
@Tag("live")
class TurnSandboxLiveTest {

    private static final Logger log = LoggerFactory.getLogger(TurnSandboxLiveTest.class);

    private static final String IMAGE =
            System.getenv().getOrDefault("HEPHAESTUS_AGENT_IMAGE_REFERENCE", "ghcr.io/hephaestus-build/agent-pi:dev");

    @TempDir
    Path temp;

    private DockerExecSandboxes runtime;
    private TurnSandboxes sandboxes;
    private TurnContextFolder folder;
    private TurnAttempt turn;
    private Instant deadline;
    private Path repository;

    @BeforeAll
    static void checkDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
    }

    @BeforeEach
    void setUp() throws Exception {
        String owner = "live-turn-" + UUID.randomUUID();
        var properties = new DockerSandboxProperties("unix:///var/run/docker.sock", false, null, null, owner);
        var client = DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(URI.create("unix:///var/run/docker.sock"))
                        .build());
        var operations = new DockerClientOperations(client, client);
        runtime = new DockerExecSandboxes(
                client,
                operations,
                new ContainerSecurityPolicy(properties, null),
                image -> {},
                new SandboxCreator(operations),
                IMAGE,
                owner,
                8080);
        sandboxes = new TurnSandboxes(runtime, 1);
        repository = repository(temp.resolve("api"));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("loader", new FixedLoader(repository));
        folder = new TurnContextFolder(sandboxes, beans.getBeanProvider(ContextLoader.class), Clock.systemUTC());
        turn = new TurnAttempt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1);
        deadline = Instant.now().plus(Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() throws IOException {
        sandboxes.close();
        runtime.close();
    }

    @Test
    void shouldStageReadRunGitAndRemoveTheSandboxWhenTheAttemptEnds() throws Exception {
        sandboxes.reconcile((turnId, attempt) -> true);
        long poolStarted = System.nanoTime();
        while (sandboxes.pooled() < 1
                && System.nanoTime() - poolStarted < Duration.ofSeconds(60).toNanos()) {
            Thread.sleep(50);
        }
        log.info("TIMING pool fill (cold start of one sandbox): {} ms", millis(poolStarted));
        assertThat(sandboxes.pooled()).isOne();

        long first = System.nanoTime();
        ObjectNode index = folder.read(turn, deadline, documents(), "INDEX.md", null, null);
        log.info("TIMING first call (acquire + stage + read): {} ms", millis(first));
        assertThat(index.path("ok").asBoolean()).as(index.toString()).isTrue();
        assertThat(index.path("text").asString()).contains("# Context folder", "inputs/context/activity.json");

        List<Long> reads = new ArrayList<>();
        for (int run = 0; run < 10; run++) {
            long started = System.nanoTime();
            ObjectNode page = folder.read(turn, deadline, documents(), "inputs/context/activity.json", 10, 5);
            reads.add(millis(started));
            assertThat(page.path("text").asString()).contains("[3988 more lines in file. Use offset=15 to continue.]");
        }
        log.info("TIMING read p50={} ms p95={} ms", percentile(reads, 50), percentile(reads, 95));

        ObjectNode item = folder.read(turn, deadline, documents(), "inputs/context/issue/7.json", null, null);
        assertThat(item.path("text").asString()).contains("\"issue\" : 7");
        assertThat(folder.read(turn, deadline, documents(), "../etc/passwd", null, null)
                        .path("error")
                        .asString())
                .isEqualTo("NOT_FOUND");
        assertThat(folder.read(turn, deadline, documents(), "inputs", null, null)
                        .path("error")
                        .asString())
                .isEqualTo("IS_DIRECTORY");

        long loadStarted = System.nanoTime();
        ObjectNode loaded = folder.load(turn, deadline, documents(), "repos/github.com/acme/api");
        log.info("TIMING load (stage of a repository with its history): {} ms", millis(loadStarted));
        assertThat(loaded.path("ok").asBoolean()).as(loaded.toString()).isTrue();
        assertThat(loaded.path("resolved").path("cite").asString()).startsWith("@");

        List<Long> execs = new ArrayList<>();
        ObjectNode history = null;
        for (int run = 0; run < 10; run++) {
            long started = System.nanoTime();
            history =
                    folder.exec(turn, deadline, documents(), "git -C repos/github.com/acme/api log --format=%s", null);
            execs.add(millis(started));
        }
        log.info("TIMING exec git log p50={} ms p95={} ms", percentile(execs, 50), percentile(execs, 95));
        assertThat(history).isNotNull();
        assertThat(history.path("exitCode").asInt()).as(history.toString()).isZero();
        assertThat(history.path("output").asString()).contains("Second commit", "First commit");

        assertThat(folder.exec(turn, deadline, documents(), "sha256sum inputs/context/user.json", null)
                        .path("output")
                        .asString())
                .as("the added document has its exact bytes")
                .startsWith(sha256("{\n  \"counts\" : 3\n}"));
        assertThat(folder.exec(turn, deadline, documents(), "sha256sum repos/github.com/acme/api/README.md", null)
                        .path("output")
                        .asString())
                .as("the loaded file has its exact bytes")
                .startsWith(sha256("# API\n\nSecond line.\n"));

        ObjectNode write = folder.exec(turn, deadline, documents(), "touch INDEX.md.new || echo refused", null);
        assertThat(write.path("output").asString()).contains("refused");
        ObjectNode append = folder.exec(
                turn, deadline, documents(), "(echo x >> inputs/context/user.json) 2>/dev/null || echo refused", null);
        assertThat(append.path("output").asString()).contains("refused");
        ObjectNode addition = folder.exec(
                turn, deadline, documents(), "touch repos/github.com/acme/api/new 2>/dev/null || echo refused", null);
        assertThat(addition.path("output").asString()).contains("refused");
        ObjectNode environment = folder.exec(turn, deadline, documents(), "env | grep -c TOKEN || true", null);
        assertThat(environment.path("output").asString().strip()).isEqualTo("0");

        ObjectNode many = folder.exec(turn, deadline, documents(), "seq 1 5000", null);
        assertThat(many.path("truncated").asBoolean()).isTrue();
        String fullOutput = many.path("fullOutputPath").asString();
        assertThat(many.path("output").asString())
                .endsWith("[Showing lines 3001-5000 of 5000. Full output: " + fullOutput + "]");
        assertThat(folder.read(turn, deadline, documents(), fullOutput, 1, 2)
                        .path("text")
                        .asString())
                .startsWith("1\n2\n");

        Files.delete(repository.resolve("README.md"));
        Files.writeString(repository.resolve("NOTES.md"), "notes\n");
        ObjectNode reloaded = folder.load(turn, deadline, documents(), "repos/github.com/acme/api");
        assertThat(reloaded.path("ok").asBoolean()).as(reloaded.toString()).isTrue();
        assertThat(folder.exec(turn, deadline, documents(), "ls repos/github.com/acme/api", null)
                        .path("output")
                        .asString())
                .as("a second load replaces the directory")
                .isEqualTo("NOTES.md\n");

        long timeoutStarted = System.nanoTime();
        ObjectNode slow = folder.exec(
                turn,
                deadline,
                documents(),
                "setsid sh -c 'echo $$ > /tmp/detached.pid; exec sleep 300' & sleep 30",
                1000);
        log.info("TIMING exec with a 1 s timeout: {} ms", millis(timeoutStarted));
        assertThat(slow.path("timedOut").asBoolean()).as(slow.toString()).isTrue();
        assertThat(millis(timeoutStarted)).isLessThan(10_000);
        ObjectNode detached = folder.exec(
                turn,
                deadline,
                documents(),
                "[ -s /tmp/detached.pid ] || { echo missing; exit 0; }; p=$(cat /tmp/detached.pid);"
                        + " for i in 1 2 3 4 5 6 7 8 9 10; do"
                        + " kill -0 \"$p\" 2>/dev/null || { echo gone; exit 0; }; sleep 0.2; done; echo alive",
                null);
        assertThat(detached.path("output").asString())
                .as("the cancellation ends a child that left the command's process group")
                .isEqualTo("gone\n");
        ObjectNode after = folder.exec(turn, deadline, documents(), "echo still here", null);
        assertThat(after.path("output").asString())
                .as("the kill of the sandbox user's processes leaves the sandbox running")
                .isEqualTo("still here\n");

        String container = held();
        sandboxes.release(turn.turnId(), turn.attempt());
        awaitGone(container);
    }

    @Test
    void shouldRemoveTheSandboxAndEndItsCommandWhenTheAttemptStopsWhileTheCommandRuns() throws Exception {
        ObjectNode first = folder.exec(turn, deadline, documents(), "true", null);
        assertThat(first.path("exitCode").isInt()).as(first.toString()).isTrue();
        assertThat(first.path("exitCode").asInt()).isZero();
        CompletableFuture<ObjectNode> running =
                CompletableFuture.supplyAsync(() -> folder.exec(turn, deadline, documents(), "sleep 50", null));
        Thread.sleep(1000);
        String container = held();

        long stopped = System.nanoTime();
        sandboxes.release(turn.turnId(), turn.attempt());
        ObjectNode ended = running.get();
        log.info("TIMING command end after the attempt stopped: {} ms", millis(stopped));

        assertThat(ended.path("ok").asBoolean()).as(ended.toString()).isFalse();
        assertThat(millis(stopped)).isLessThan(15_000);
        awaitGone(container);
    }

    /** The attempt's sandbox, which its first call took. */
    private String held() {
        return Objects.requireNonNull(
                sandboxes.acquire(new TurnSandboxes.Attempt(turn.turnId(), turn.attempt()), turn.workspaceId())
                        .containerId);
    }

    private void awaitGone(String container) throws InterruptedException {
        long started = System.nanoTime();
        while (runtime.list().stream().anyMatch(listed -> listed.id().equals(container))
                && System.nanoTime() - started < Duration.ofSeconds(30).toNanos()) {
            Thread.sleep(100);
        }
        assertThat(runtime.list())
                .as("the attempt's sandbox is removed")
                .noneMatch(listed -> listed.id().equals(container));
    }

    private static TurnDocuments documents() {
        StringBuilder activity = new StringBuilder("{\n");
        for (int line = 1; line < 4000; line++) {
            activity.append("  \"line")
                    .append(line)
                    .append("\" : ")
                    .append(line)
                    .append(",\n");
        }
        activity.append("  \"end\" : true\n}");
        Map<String, byte[]> built = new LinkedHashMap<>();
        built.put("inputs/context/activity.json", activity.toString().getBytes(StandardCharsets.UTF_8));
        built.put("inputs/context/user.json", "{\n  \"counts\" : 3\n}".getBytes(StandardCharsets.UTF_8));
        return new TurnDocuments() {
            @Override
            public Map<String, byte[]> built() {
                return built;
            }

            @Override
            public Optional<byte[]> item(String path) {
                return path.equals("inputs/context/issue/7.json")
                        ? Optional.of("{\n  \"issue\" : 7\n}".getBytes(StandardCharsets.UTF_8))
                        : Optional.empty();
            }

            @Override
            public String builtAt() {
                return "Tue 2026-10-20 10:00";
            }
        };
    }

    private static Path repository(Path directory) throws Exception {
        try (Git git = Git.init()
                .setDirectory(directory.toFile())
                .setInitialBranch("main")
                .call()) {
            Files.writeString(directory.resolve("README.md"), "# API\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("First commit")
                    .setAuthor("A", "a@example.invalid")
                    .setSign(false)
                    .call();
            Files.writeString(directory.resolve("README.md"), "# API\n\nSecond line.\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Second commit")
                    .setAuthor("A", "a@example.invalid")
                    .setSign(false)
                    .call();
        }
        return directory;
    }

    /** Loads the one test repository at its head, as the SCM loader stages a checkout. */
    private record FixedLoader(Path directory) implements ContextLoader {
        @Override
        public String prefix() {
            return "repos/";
        }

        @Override
        public String index(long workspaceId, long accountId) {
            return "## repos/\n\n- `repos/github.com/acme/api`\n";
        }

        @Override
        public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
            Supplier<String> head = () -> {
                try (Git git = Git.open(directory.toFile())) {
                    return Objects.requireNonNull(git.getRepository().resolve("HEAD"))
                            .name();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
            String sha = head.get();
            return new Loaded() {
                @Override
                public String path() {
                    return "repos/github.com/acme/api";
                }

                @Override
                public Path directory() {
                    return directory;
                }

                @Override
                public Version version() {
                    return new Version("main", sha, "@" + sha.substring(0, 7));
                }

                @Override
                public String note() {
                    return "Loaded.";
                }

                @Override
                public void close() {}
            };
        }
    }

    private static String sha256(String content) throws NoSuchAlgorithmException {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static long millis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static long percentile(List<Long> values, int percentile) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
