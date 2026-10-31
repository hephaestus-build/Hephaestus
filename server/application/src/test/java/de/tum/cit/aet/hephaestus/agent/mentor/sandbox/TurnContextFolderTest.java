package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.node.ObjectNode;

class TurnContextFolderTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(5));

    @TempDir
    Path temp;

    private final FakeExecSandboxes runtime = new FakeExecSandboxes();
    private final TurnSandboxes sandboxes = new TurnSandboxes(runtime, 0, new SimpleMeterRegistry());
    private final List<List<String>> staged = new ArrayList<>();
    private final Map<String, String> stagedFiles = new LinkedHashMap<>();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final TurnContextFolder folder = new TurnContextFolder(
            sandboxes, beans.getBeanProvider(ContextLoader.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void close() {
        sandboxes.close();
    }

    private static TurnAttempt turn() {
        return new TurnAttempt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1);
    }

    private static TurnDocuments documents() {
        Map<String, byte[]> built = new LinkedHashMap<>();
        built.put("inputs/context/user.json", "{\n  \"counts\" : 3\n}".getBytes(StandardCharsets.UTF_8));
        return new TurnDocuments() {
            @Override
            public Map<String, byte[]> built() {
                return built;
            }

            @Override
            public Optional<byte[]> item(String path) {
                return path.equals("inputs/context/issue/7.json")
                        ? Optional.of("{}".getBytes(StandardCharsets.UTF_8))
                        : Optional.empty();
            }

            @Override
            public String builtAt() {
                return "Tue 2026-10-20 12:00";
            }
        };
    }

    /** Answers as the sandbox would: staging records its archive, a read finds the staged files. */
    private void sandboxAnswers(String execStdout) {
        runtime.onStage = staging -> staged.add(entries(staging.archive()));
        runtime.answer = command -> {
            String script = command.argv().get(2);
            if (script.equals(TurnContextFolder.READ_SCRIPT)) {
                String file = command.argv().get(4);
                boolean present =
                        staged.stream().flatMap(List::stream).anyMatch(entry -> ("/workspace/" + entry).equals(file));
                return present ? execution(0, "1 1\n{\n") : execution(3, "");
            }
            if (script.equals(TurnContextFolder.EXEC_SCRIPT)) {
                return execution(0, execStdout);
            }
            return execution(0, "");
        };
    }

    private static ExecSandboxes.Execution execution(int exit, String stdout) {
        return new ExecSandboxes.Execution(exit, stdout.getBytes(StandardCharsets.UTF_8), new byte[0], false);
    }

    /** The files of a staged archive, by name; their contents go to {@link #stagedFiles}. */
    private List<String> entries(InputStream archive) {
        List<String> names = new ArrayList<>();
        try (var tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                    stagedFiles.put(entry.getName(), new String(tar.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    @Test
    void shouldStageTheIndexTheFieldGuideAndTheDocumentsOnceWhenTheAttemptFirstReads() {
        sandboxAnswers("");
        TurnAttempt turn = turn();

        ObjectNode first = folder.read(turn, DEADLINE, documents(), "INDEX.md", null, null);
        ObjectNode second = folder.read(turn, DEADLINE, documents(), "/workspace/inputs/context/user.json", null, null);

        assertThat(first.path("ok").asBoolean()).as(first.toString()).isTrue();
        assertThat(second.path("text").asString()).isEqualTo("{\n");
        assertThat(staged).containsExactly(List.of("inputs/context/user.json", "FIELDS.md", "INDEX.md"));
        assertThat(runtime.stagings)
                .singleElement()
                .isInstanceOfSatisfying(
                        ExecSandboxes.Add.class,
                        add -> assertThat(add.names()).containsExactly("inputs", "FIELDS.md", "INDEX.md"));
        assertThat(stagedFiles).containsEntry("inputs/context/user.json", "{\n  \"counts\" : 3\n}");
        assertThat(runtime.createdCount()).isOne();
    }

    @Test
    void shouldStageAnItemDocumentFromItsSourceWhenTheModelReadsItsPath() {
        sandboxAnswers("");
        TurnAttempt turn = turn();

        ObjectNode item = folder.read(turn, DEADLINE, documents(), "inputs/context/issue/7.json", null, null);
        ObjectNode unknown = folder.read(turn, DEADLINE, documents(), "inputs/context/issue/8.json", null, null);

        assertThat(item.path("ok").asBoolean()).as(item.toString()).isTrue();
        assertThat(staged.getLast()).containsExactly("inputs/context/issue/7.json");
        assertThat(runtime.stagings.getLast())
                .isInstanceOfSatisfying(
                        ExecSandboxes.Add.class, add -> assertThat(add.names()).containsExactly("inputs"));
        assertThat(unknown.path("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(unknown.path("detail").asString()).isEqualTo("File not found: inputs/context/issue/8.json");
    }

    @Test
    void shouldRefuseAPathOutsideTheFolderWithoutStartingASandbox() {
        ObjectNode refused = folder.read(turn(), NOW, documents(), "../etc/passwd", null, null);

        assertThat(refused.path("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(refused.path("detail").asString()).isEqualTo("File not found: ../etc/passwd");
        assertThat(runtime.createdCount()).isZero();
    }

    @Test
    void shouldBoundTheCommandByTheTurnDeadlineWhenLessThanAMinuteIsLeft() {
        sandboxAnswers("0 1 3 3\nok\n");

        ObjectNode ran = folder.exec(turn(), NOW.plusSeconds(20), documents(), "git log", 120_000);

        assertThat(ran.path("exitCode").asInt()).isZero();
        assertThat(ran.path("output").asString()).isEqualTo("ok\n");
        ExecSandboxes.Command command = runtime.commands.getLast();
        assertThat(command.argv()).containsSubsequence(TurnContextFolder.EXEC_SCRIPT, "exec", "1", "20.000", "git log");
        assertThat(command.timeout()).isLessThanOrEqualTo(Duration.ofSeconds(25));
    }

    @Test
    void shouldBoundTheCommandByOneMinuteWhenTheModelAsksForMore() {
        sandboxAnswers("0 0 0 0\n");

        folder.exec(turn(), NOW.plusSeconds(300), documents(), "sleep 100", 600_000);

        assertThat(runtime.commands.getLast().argv()).contains("60.000");
    }

    @Test
    void shouldKillTheCommandsWhenTheSandboxDoesNotAnswerByTheTimeoutAndItsMargin() {
        sandboxAnswers("");
        TurnAttempt turn = turn();
        folder.read(turn, DEADLINE, documents(), "INDEX.md", null, null);
        runtime.answer = command -> command.argv().get(2).equals(TurnContextFolder.EXEC_SCRIPT)
                ? new ExecSandboxes.Execution(null, new byte[0], new byte[0], false)
                : execution(0, "");

        ObjectNode ran = folder.exec(turn, DEADLINE, documents(), "sleep 100", 1000);

        assertThat(ran.path("timedOut").asBoolean()).isTrue();
        assertThat(ran.path("exitCode").isNull()).isTrue();
        assertThat(runtime.cancelled).containsExactly("container-1");
    }

    @Test
    void shouldReturnTheTailAndNameTheFullOutputWhenTheOutputIsTooLong() {
        StringBuilder tail = new StringBuilder();
        for (int line = 1; line <= 3000; line++) tail.append(line).append('\n');
        int bytes = tail.length();
        sandboxAnswers("0 3000 " + bytes + " 5\n" + tail);

        ObjectNode ran = folder.exec(turn(), NOW.plusSeconds(300), documents(), "seq 1 3000", null);

        assertThat(ran.path("truncated").asBoolean()).isTrue();
        assertThat(ran.path("fullOutputPath").asString()).isEqualTo("/tmp/heph-out/1.log");
        assertThat(ran.path("output").asString())
                .endsWith("[Showing lines 1001-3000 of 3000. Full output: /tmp/heph-out/1.log]");
    }

    @Test
    void shouldStageALoadedItemAndAnswerItsVersionWhenTheLoaderOwnsThePath() throws IOException {
        sandboxAnswers("");
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        Files.writeString(checkout.resolve("README.md"), "# API\n");
        beans.registerSingleton("loader", new Loader(checkout));
        TurnAttempt turn = turn();

        ObjectNode loaded = folder.load(turn, DEADLINE, documents(), "repos/github.com/acme/api@main");

        assertThat(loaded.path("ok").asBoolean()).as(loaded.toString()).isTrue();
        assertThat(loaded.path("path").asString()).isEqualTo("repos/github.com/acme/api");
        assertThat(loaded.path("resolved").path("cite").asString()).isEqualTo("@abcdef1");
        assertThat(staged.getLast()).containsExactly("README.md");
        assertThat(stagedFiles).containsEntry("README.md", "# API\n");
        assertThat(runtime.stagings.getLast())
                .isInstanceOfSatisfying(
                        ExecSandboxes.Replace.class,
                        replace -> assertThat(replace.directory()).isEqualTo("repos/github.com/acme/api"));
        assertThat(folder.load(turn, DEADLINE, documents(), "repos/github.com/acme/hidden")
                        .path("error")
                        .asString())
                .isEqualTo("NOT_VISIBLE");
        assertThat(folder.load(turn, DEADLINE, documents(), "elsewhere/x")
                        .path("error")
                        .asString())
                .isEqualTo("INVALID_PATH");
    }

    @Test
    void shouldAddTheLoadersSummaryRunInTheStagedDirectoryToTheNoteWhenALoadIsStaged() throws IOException {
        sandboxAnswers("");
        var staging = runtime.answer;
        runtime.answer = command -> command.argv().contains("git-summary")
                ? new ExecSandboxes.Execution(
                        0, "Merge base with main: abcdef123456\n".getBytes(StandardCharsets.UTF_8), new byte[0], false)
                : staging.apply(command);
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        beans.registerSingleton("loader", new Loader(checkout) {
            @Override
            public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
                Loaded loaded = super.load(workspaceId, accountId, path, abandoned);
                return new Loaded() {
                    @Override
                    public String path() {
                        return loaded.path();
                    }

                    @Override
                    public Path directory() {
                        return loaded.directory();
                    }

                    @Override
                    public Version version() {
                        return loaded.version();
                    }

                    @Override
                    public String note() {
                        return loaded.note();
                    }

                    @Override
                    public List<String> summary() {
                        return List.of("git-summary", "main");
                    }

                    @Override
                    public void close() {
                        loaded.close();
                    }
                };
            }
        });
        TurnAttempt turn = turn();

        ObjectNode loaded = folder.load(turn, DEADLINE, documents(), "repos/github.com/acme/api@pull/1");

        assertThat(loaded.path("note").asString()).isEqualTo("Loaded.\nMerge base with main: abcdef123456");
        ExecSandboxes.Command summary = runtime.commands.getLast();
        assertThat(summary.argv()).containsSubsequence("/workspace/repos/github.com/acme/api", "git-summary", "main");
    }

    @Test
    void shouldRefuseTheSixthLoadOfAnAttempt() throws IOException {
        sandboxAnswers("");
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        beans.registerSingleton("loader", new Loader(checkout));
        TurnAttempt turn = turn();

        for (int load = 0; load < TurnContextFolder.MAX_LOADS_PER_ATTEMPT; load++) {
            assertThat(folder.load(turn, DEADLINE, documents(), "repos/github.com/acme/api")
                            .path("ok")
                            .asBoolean())
                    .isTrue();
        }

        assertThat(folder.load(turn, DEADLINE, documents(), "repos/github.com/acme/api")
                        .path("error")
                        .asString())
                .isEqualTo("BUDGET_EXHAUSTED");
    }

    @Test
    void shouldSayThatTheContextChangedWhenTheWorkspaceDataChangedDuringTheAttempt() {
        sandboxAnswers("0 0 0 0\n");
        TurnAttempt turn = turn();
        folder.exec(turn, DEADLINE, documents(), "true", null);

        sandboxes.releaseWorkspace(turn.workspaceId());

        assertThat(folder.exec(turn, DEADLINE, documents(), "true", null)
                        .path("error")
                        .asString())
                .isEqualTo("CONTEXT_CHANGED");
        assertThat(folder.exec(turn, DEADLINE, documents(), "true", null)
                        .path("ok")
                        .asBoolean())
                .isTrue();
        assertThat(runtime.createdCount()).isEqualTo(2);
    }

    @Test
    void shouldKillEveryProcessOfTheSandboxUserWhenTheCommandTimedOut() {
        sandboxAnswers("124 0 0 0\n");

        ObjectNode ran = folder.exec(turn(), NOW.plusSeconds(300), documents(), "sleep 100", 1000);

        assertThat(ran.path("timedOut").asBoolean()).isTrue();
        assertThat(runtime.cancelled)
                .as("a child that left the process group dies too")
                .containsExactly("container-1");
    }

    @Test
    void shouldRemoveTheSandboxAndAnswerUnavailableWhenALoadFailsToStage() throws IOException {
        sandboxAnswers("");
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        beans.registerSingleton("loader", new Loader(checkout));
        TurnAttempt turn = turn();
        folder.read(turn, DEADLINE, documents(), "INDEX.md", null, null);
        var stage = runtime.onStage;
        runtime.onStage = staging -> {
            if (staging instanceof ExecSandboxes.Replace) {
                throw new SandboxException("Staging into the turn sandbox failed: exit 2");
            }
            stage.accept(staging);
        };

        ObjectNode failed = folder.load(turn, DEADLINE, documents(), "repos/github.com/acme/api");

        assertThat(failed.path("error").asString()).isEqualTo("UNAVAILABLE");
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.removed.contains("container-1"));
        staged.clear();
        assertThat(folder.read(turn, DEADLINE, documents(), "INDEX.md", null, null)
                        .path("ok")
                        .asBoolean())
                .isTrue();
        assertThat(runtime.createdCount())
                .as("the next call gets a new sandbox")
                .isEqualTo(2);
        assertThat(staged.getFirst()).as("and stages the documents again").contains("INDEX.md");
    }

    @Test
    void shouldRemoveTheSandboxAndStageANewOneWhenACommandsRunFails() {
        sandboxAnswers("0 0 0 0\n");
        TurnAttempt turn = turn();
        folder.read(turn, DEADLINE, documents(), "INDEX.md", null, null);
        var answers = runtime.answer;
        runtime.answer = command -> {
            if (command.argv().get(2).equals(TurnContextFolder.EXEC_SCRIPT)) {
                // What the runtime says when it cannot tell whether the command started: it may start later.
                throw new SandboxException(
                        "A turn sandbox command did not open before its deadline and may still start");
            }
            return answers.apply(command);
        };

        ObjectNode failed = folder.exec(turn, DEADLINE, documents(), "true", null);

        assertThat(failed.path("error").asString()).isEqualTo("UNAVAILABLE");
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.removed.contains("container-1"));
        runtime.answer = answers;
        staged.clear();
        assertThat(folder.exec(turn, DEADLINE, documents(), "true", null)
                        .path("ok")
                        .asBoolean())
                .isTrue();
        assertThat(runtime.createdCount())
                .as("the next command runs in a new sandbox, which a late start of the failed one cannot reach")
                .isEqualTo(2);
        assertThat(staged.getFirst()).as("and the documents are staged again").contains("INDEX.md");
    }

    @Test
    void shouldRemoveTheSandboxWhenTheCancellationOfATimedOutCommandFails() {
        sandboxAnswers("0 0 0 0\n");
        TurnAttempt turn = turn();
        folder.read(turn, DEADLINE, documents(), "INDEX.md", null, null);
        var answers = runtime.answer;
        runtime.answer = command -> {
            if (command.argv().get(2).equals(TurnContextFolder.EXEC_SCRIPT)) {
                // The command outlives its timeout, and the runtime then cannot reach the sandbox to end it.
                runtime.running.remove("container-1");
                return new ExecSandboxes.Execution(null, new byte[0], new byte[0], false);
            }
            return answers.apply(command);
        };

        ObjectNode failed = folder.exec(turn, DEADLINE, documents(), "sleep 100", 1000);

        assertThat(failed.path("error").asString()).isEqualTo("UNAVAILABLE");
        assertThat(runtime.cancelled).as("no cancellation was confirmed").isEmpty();
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.removed.contains("container-1"));
        runtime.answer = answers;
        staged.clear();
        assertThat(folder.exec(turn, DEADLINE, documents(), "true", null)
                        .path("ok")
                        .asBoolean())
                .isTrue();
        assertThat(runtime.createdCount())
                .as("a late kill of the first sandbox reaches no later command")
                .isEqualTo(2);
        assertThat(staged.getFirst()).as("and the documents are staged again").contains("INDEX.md");
    }

    @Test
    void shouldStageALoadOnlyWhenNoCommandRunsInTheFolder() throws Exception {
        sandboxAnswers("0 0 0 0\n");
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        beans.registerSingleton("loader", new Loader(checkout));
        TurnAttempt turn = turn();
        folder.read(turn, DEADLINE, documents(), "INDEX.md", null, null);
        CountDownLatch commandRuns = new CountDownLatch(1);
        CountDownLatch commandEnds = new CountDownLatch(1);
        var answers = runtime.answer;
        runtime.answer = command -> {
            if (command.argv().get(2).equals(TurnContextFolder.EXEC_SCRIPT)) {
                commandRuns.countDown();
                waitFor(commandEnds);
            }
            return answers.apply(command);
        };
        CompletableFuture<ObjectNode> running =
                CompletableFuture.supplyAsync(() -> folder.exec(turn, DEADLINE, documents(), "git log", null));
        commandRuns.await();

        CompletableFuture<ObjectNode> loading = CompletableFuture.supplyAsync(
                () -> folder.load(turn, DEADLINE, documents(), "repos/github.com/acme/api"));
        Thread.sleep(300);
        assertThat(runtime.stagings)
                .as("the load waits for the command")
                .noneMatch(ExecSandboxes.Replace.class::isInstance);

        commandEnds.countDown();
        assertThat(running.get().path("ok").asBoolean()).isTrue();
        assertThat(loading.get().path("ok").asBoolean()).isTrue();
    }

    @Test
    void shouldKeepTheLoadPermitUntilAnAbandonedFetchEnds() {
        sandboxAnswers("");
        CountDownLatch fetch = new CountDownLatch(1);
        beans.registerSingleton("loader", new Loader(temp) {
            @Override
            public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
                waitFor(fetch);
                return super.load(workspaceId, accountId, path, abandoned);
            }
        });
        TurnAttempt turn = turn();
        folder.read(turn, DEADLINE, documents(), "INDEX.md", null, null);

        ObjectNode ended = folder.load(turn, NOW.plusMillis(300), documents(), "repos/github.com/acme/api");

        assertThat(ended.path("error").asString()).isEqualTo("TIMEOUT");
        assertThat(folder.loadsRunning())
                .as("the fetch still runs and keeps its permit")
                .isOne();
        fetch.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> folder.loadsRunning() == 0);
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void shouldSummariseUnderTheSameWriteLockAsTheStagingAndKeepTheSandboxWhenTheSummaryTimesOut() throws IOException {
        sandboxAnswers("0 0 0 0\n");
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        beans.registerSingleton("loader", new Loader(checkout) {
            @Override
            public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
                return summarised(super.load(workspaceId, accountId, path, abandoned));
            }
        });
        TurnAttempt turn = turn();
        TurnSandboxes.Sandbox sandbox =
                sandboxes.acquire(new TurnSandboxes.Attempt(turn.turnId(), turn.attempt()), 1L, () -> false);
        var answers = runtime.answer;
        List<Boolean> lockedDuringSummary = new ArrayList<>();
        runtime.answer = command -> {
            if (command.argv().contains("git-summary")) {
                lockedDuringSummary.add(sandbox.lock.isWriteLocked());
                return new ExecSandboxes.Execution(null, new byte[0], new byte[0], false);
            }
            return answers.apply(command);
        };

        ObjectNode loaded = folder.load(turn, DEADLINE, documents(), "repos/github.com/acme/api@pull/1");

        assertThat(lockedDuringSummary)
                .as("no other load stages between the staging and its summary")
                .containsExactly(true);
        assertThat(loaded.path("ok").asBoolean()).isTrue();
        assertThat(loaded.path("note").asString())
                .as("a summary that timed out is left out")
                .isEqualTo("Loaded.");
        assertThat(runtime.cancelled).as("its processes end").containsExactly(sandbox.containerId());
        assertThat(folder.exec(turn, DEADLINE, documents(), "true", null)
                        .path("ok")
                        .asBoolean())
                .as("the sandbox stays in use")
                .isTrue();
        assertThat(runtime.createdCount()).isOne();
    }

    @Test
    void shouldStopWaitingForTheFolderAtTheTurnsDeadline() throws Exception {
        sandboxAnswers("0 0 0 0\n");
        TurnAttempt turn = turn();
        folder.exec(turn, DEADLINE, documents(), "true", null);
        TurnSandboxes.Sandbox sandbox =
                sandboxes.acquire(new TurnSandboxes.Attempt(turn.turnId(), turn.attempt()), 1L, () -> false);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread loading = Thread.ofVirtual().start(() -> {
            sandbox.lock.writeLock().lock();
            try {
                held.countDown();
                waitFor(done);
            } finally {
                sandbox.lock.writeLock().unlock();
            }
        });
        held.await();

        ObjectNode late = folder.exec(turn, NOW.plusMillis(200), documents(), "true", null);

        assertThat(late.path("error").asString()).isEqualTo("TIMEOUT");
        done.countDown();
        loading.join();
        sandboxAnswers("");
        folder.read(turn, NOW.plusSeconds(5), documents(), "INDEX.md", null, null);
        assertThat(runtime.commands.getLast().timeout())
                .as("a read gets no more than the turn has left")
                .isLessThanOrEqualTo(Duration.ofSeconds(5));
    }

    private static ContextLoader.Loaded summarised(ContextLoader.Loaded loaded) {
        return new ContextLoader.Loaded() {
            @Override
            public String path() {
                return loaded.path();
            }

            @Override
            public Path directory() {
                return loaded.directory();
            }

            @Override
            public ContextLoader.Version version() {
                return loaded.version();
            }

            @Override
            public String note() {
                return loaded.note();
            }

            @Override
            public List<String> summary() {
                return List.of("git-summary", "main");
            }

            @Override
            public void close() {
                loaded.close();
            }
        };
    }

    /** Loads {@code repos/github.com/acme/api} from a fixed directory and refuses everything else. */
    private static class Loader implements ContextLoader {
        private final Path directory;

        Loader(Path directory) {
            this.directory = directory;
        }

        @Override
        public String prefix() {
            return "repos/";
        }

        @Override
        public String index(long workspaceId, long accountId) {
            return "## repos/\n";
        }

        @Override
        public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
            if (!path.startsWith("repos/github.com/acme/api")) {
                throw new ContextLoadException(ContextLoadException.Code.NOT_VISIBLE, "No repository at " + path);
            }
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
                    return new Version("main", "abcdef1234567890abcdef1234567890abcdef12", "@abcdef1");
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
}
