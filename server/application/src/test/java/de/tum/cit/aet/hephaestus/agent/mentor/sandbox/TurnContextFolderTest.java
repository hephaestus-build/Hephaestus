package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The context folder over a mocked tools Pod binder and turn rows. {@code ConversationToolsPodsIntegrationTest} owns
 * the binds, and {@code MentorTurnRepository#countToolsLoad} is fenced in SQL.
 */
class TurnContextFolderTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(5));

    @TempDir
    Path temp;

    private final ConversationToolsPods toolsPods = mock(ConversationToolsPods.class);
    private final MentorTurnRepository turns = mock(MentorTurnRepository.class);
    private final MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final TurnContextFolder folder =
            new TurnContextFolder(toolsPods, turns, beans.getBeanProvider(ContextLoader.class), clock);

    /** Whether the attempt holds its live lease, as its turn row says. */
    private final AtomicBoolean leased = new AtomicBoolean(true);

    /** The loads that the turn row counted. */
    private final AtomicInteger loads = new AtomicInteger();

    @BeforeEach
    void leaseTheAttempt() {
        lenient().when(turns.hasLiveLease(any(), anyLong(), anyInt(), any())).thenAnswer(call -> leased.get());
        lenient()
                .when(turns.countToolsLoad(any(), anyLong(), anyInt(), any(), anyInt()))
                .thenAnswer(call -> {
                    if (!leased.get() || loads.get() >= call.<Integer>getArgument(4)) return 0;
                    loads.incrementAndGet();
                    return 1;
                });
    }

    private static TurnAttempt turn() {
        return new TurnAttempt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1, UUID.randomUUID());
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

    private static String text(ObjectNode answer) {
        assertThat(answer.path("ok").asBoolean()).as(answer.toString()).isTrue();
        return answer.path("text").asString();
    }

    @Test
    void shouldServeTheIndexTheFieldGuideAndTheDocumentsFromMemoryWithoutAToolsPod() {
        beans.registerSingleton("loader", new Loader(temp));
        TurnAttempt turn = turn();

        String index = text(folder.document(turn, documents(), "INDEX.md", null, null));
        String fields = text(folder.document(turn, documents(), "/tmp/tools/workspace/FIELDS.md", null, null));
        String user = text(folder.document(turn, documents(), "inputs/context/user.json", null, null));
        String item = text(folder.document(turn, documents(), "inputs/context/issue/7.json", null, null));

        assertThat(index).contains("inputs/context/user.json").contains("## repos/");
        assertThat(fields).isNotBlank();
        assertThat(user).isEqualTo("{\n  \"counts\" : 3\n}");
        assertThat(item).as("an item document is read from its source").isEqualTo("{}");
    }

    @Test
    void shouldRefuseAPathThatIsNoDocumentOrNotThere() {
        TurnAttempt turn = turn();

        for (String path :
                List.of("repos/github.com/acme/api/README.md", "../etc/passwd", "inputs/context/gone.json")) {
            assertThat(folder.document(turn, documents(), path, null, null)
                            .path("error")
                            .asString())
                    .as(path)
                    .isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void shouldPageADocumentAsPisReadPagesAFile() {
        Map<String, byte[]> built = new LinkedHashMap<>();
        StringBuilder lines = new StringBuilder();
        for (int line = 1; line <= 30; line++) {
            lines.append("line ").append(line).append('\n');
        }
        built.put("inputs/context/long.json", lines.toString().getBytes(StandardCharsets.UTF_8));
        TurnDocuments documents = new TurnDocuments() {
            @Override
            public Map<String, byte[]> built() {
                return built;
            }

            @Override
            public Optional<byte[]> item(String path) {
                return Optional.empty();
            }

            @Override
            public String builtAt() {
                return "now";
            }
        };
        TurnAttempt turn = turn();

        assertThat(text(folder.document(turn, documents, "inputs/context/long.json", 5, 3)))
                .isEqualTo("line 5\nline 6\nline 7\n\n[24 more lines in file. Use offset=8 to continue.]");
        assertThat(folder.document(turn, documents, "inputs/context/long.json", 40, null)
                        .path("error")
                        .asString())
                .isEqualTo("OFFSET_BEYOND_END");
    }

    @Test
    void shouldArchiveTheBuiltDocumentsUnderInputsForTheToolsPod() throws IOException {
        Map<String, String> entries = entries(new ByteArrayInputStream(folder.documentsArchive(documents())));

        assertThat(entries).containsExactly(Map.entry("context/user.json", "{\n  \"counts\" : 3\n}"));
    }

    @Test
    void shouldAnswerWhereTheHostDialsTheAttemptsToolsPodWhenItBindsOne() {
        TurnAttempt turn = turn();
        when(toolsPods.bind(any(), any()))
                .thenReturn(new ToolsPods.Started("hephaestus-tools-a", "10.0.0.1", ToolsPods.PORT, "nonce-1"));

        ObjectNode bound = folder.bind(turn, DEADLINE);

        assertThat(bound.path("ok").asBoolean()).as(bound.toString()).isTrue();
        assertThat(bound.path("id").asString()).isEqualTo("hephaestus-tools-a");
        assertThat(bound.path("host").asString()).isEqualTo("10.0.0.1");
        assertThat(bound.path("port").asInt()).isEqualTo(7100);
        assertThat(bound.path("nonce").asString()).isEqualTo("nonce-1");
    }

    @Test
    void shouldAnswerContextChangedUnavailableOrTimeoutWhenNoToolsPodBinds() {
        TurnAttempt turn = turn();
        when(toolsPods.bind(any(), any()))
                .thenThrow(new ConversationToolsPods.ContextChanged())
                .thenThrow(new SandboxException("no Kubernetes here"));

        assertThat(folder.bind(turn, DEADLINE).path("error").asString()).isEqualTo("CONTEXT_CHANGED");
        assertThat(folder.bind(turn, DEADLINE).path("error").asString()).isEqualTo("UNAVAILABLE");
        doThrow(new SandboxException("the wait ended")).when(toolsPods).bind(any(), any());
        assertThat(folder.bind(turn, NOW).path("error").asString()).isEqualTo("TIMEOUT");
    }

    @Test
    void shouldAnswerTheLoadedItemAsAnArchiveWithItsVersionSizeAndSha256() throws Exception {
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        Files.writeString(checkout.resolve("README.md"), "# API\n");
        beans.registerSingleton("loader", new Loader(checkout));
        TurnAttempt turn = turn();

        TurnContextFolder.Load load = folder.load(turn, DEADLINE, "repos/github.com/acme/api@main");

        assertThat(load).isInstanceOf(TurnContextFolder.Prepared.class);
        TurnContextFolder.Prepared prepared = (TurnContextFolder.Prepared) load;
        try {
            assertThat(prepared.answer().path("path").asString()).isEqualTo("repos/github.com/acme/api");
            assertThat(prepared.answer().path("resolved").path("cite").asString())
                    .isEqualTo("@abcdef1");
            assertThat(prepared.answer().path("note").asString()).isEqualTo("Loaded.");
            assertThat(prepared.answer().path("summary").values()).hasSize(4);
            byte[] archive = Files.readAllBytes(prepared.archive());
            assertThat(prepared.size()).isEqualTo(archive.length);
            assertThat(prepared.sha256())
                    .isEqualTo(HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
            assertThat(entries(new ByteArrayInputStream(archive))).containsExactly(Map.entry("README.md", "# API\n"));
        } finally {
            prepared.delete();
        }
        assertThat(prepared.archive()).doesNotExist();
        assertThat(refusal(folder.load(turn, DEADLINE, "repos/github.com/acme/hidden")))
                .isEqualTo("NOT_VISIBLE");
        assertThat(refusal(folder.load(turn, DEADLINE, "elsewhere/x"))).isEqualTo("INVALID_PATH");
    }

    @Test
    void shouldRefuseTheSixthLoadOfAnAttempt() throws IOException {
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        beans.registerSingleton("loader", new Loader(checkout));
        TurnAttempt turn = turn();

        for (int load = 0; load < TurnContextFolder.MAX_LOADS_PER_ATTEMPT; load++) {
            TurnContextFolder.Load loaded = folder.load(turn, DEADLINE, "repos/github.com/acme/api");
            assertThat(loaded).isInstanceOf(TurnContextFolder.Prepared.class);
            ((TurnContextFolder.Prepared) loaded).delete();
        }

        assertThat(refusal(folder.load(turn, DEADLINE, "repos/github.com/acme/api")))
                .isEqualTo("BUDGET_EXHAUSTED");
    }

    @Test
    void shouldKeepTheLoadPermitUntilAnAbandonedFetchEnds() {
        CountDownLatch fetch = new CountDownLatch(1);
        beans.registerSingleton("loader", new Loader(temp) {
            @Override
            public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
                waitFor(fetch);
                return super.load(workspaceId, accountId, path, abandoned);
            }
        });
        TurnAttempt turn = turn();

        TurnContextFolder.Load ended = folder.load(turn, NOW.plusMillis(300), "repos/github.com/acme/api");

        assertThat(refusal(ended)).isEqualTo("TIMEOUT");
        assertThat(folder.loadsRunning())
                .as("the fetch still runs and keeps its permit")
                .isOne();
        fetch.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> folder.loadsRunning() == 0);
    }

    @Test
    void shouldTellTheLoaderThatTheAttemptEndedWhileItFetches() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        beans.registerSingleton("loader", new Loader(temp) {
            @Override
            public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
                started.countDown();
                waitFor(released);
                if (abandoned.getAsBoolean()) {
                    throw new ContextLoadException(ContextLoadException.Code.UNAVAILABLE, "abandoned");
                }
                return super.load(workspaceId, accountId, path, abandoned);
            }
        });
        TurnAttempt turn = turn();
        Thread.startVirtualThread(() -> {
            waitFor(started);
            // The attempt ended; the load reads its lease again after two seconds.
            leased.set(false);
            clock.advance(Duration.ofSeconds(2));
            released.countDown();
        });

        assertThat(refusal(folder.load(turn, DEADLINE, "repos/github.com/acme/api")))
                .isEqualTo("UNAVAILABLE");
    }

    private static String refusal(TurnContextFolder.Load load) {
        assertThat(load).isInstanceOf(TurnContextFolder.Refused.class);
        return ((TurnContextFolder.Refused) load).answer().path("error").asString();
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The files of an archive, by name, with their contents. */
    private static Map<String, String> entries(InputStream archive) {
        Map<String, String> files = new LinkedHashMap<>();
        try (var tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), new String(tar.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
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
                public List<String> summary() {
                    return List.of("git", "log", "--oneline", "-5");
                }

                @Override
                public void close() {}
            };
        }
    }
}
