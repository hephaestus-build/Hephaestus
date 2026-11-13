package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.EvidenceSpool;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcOperations;

/** Context loader archives, budgets and cancellation while the turn holds its run lease. */
class TurnContextLoadsTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(5));

    @TempDir
    Path temp;

    private final MentorTurnRepository turns = mock(MentorTurnRepository.class);
    private final MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final PersonDataCopyFence copies = mock(PersonDataCopyFence.class);
    private final AtomicInteger captures = new AtomicInteger();
    private FabricLayout layout;
    private TurnContextLoads folder;

    /** Whether the attempt holds its live lease, as its turn row says. */
    private final AtomicBoolean leased = new AtomicBoolean(true);

    /** The loads that the turn row counted. */
    private final AtomicInteger loads = new AtomicInteger();

    @BeforeEach
    void leaseTheAttempt() {
        layout = new FabricLayout(temp.toString());
        folder = new TurnContextLoads(turns, beans.getBeanProvider(ContextLoader.class), clock, copies, layout);
        lenient().when(copies.capture()).thenAnswer(unused -> {
            captures.incrementAndGet();
            return new PersonDataCopyFence.Lease() {
                private boolean closed;

                @Override
                public JdbcOperations jdbc() {
                    throw new UnsupportedOperationException("This fixture owns no JDBC connection");
                }

                @Override
                public synchronized void close() {
                    if (closed) return;
                    closed = true;
                    captures.decrementAndGet();
                }
            };
        });
        lenient().when(turns.hasLiveLease(any(), anyLong(), anyInt(), any())).thenAnswer(call -> leased.get());
        lenient()
                .when(turns.countToolsLoad(any(), anyLong(), anyInt(), any(), anyInt()))
                .thenAnswer(call -> {
                    if (!leased.get() || loads.get() >= call.<Integer>getArgument(4)) return 0;
                    loads.incrementAndGet();
                    return 1;
                });
    }

    @AfterEach
    void stopPreparations() {
        folder.close();
    }

    private static TurnAttempt turn() {
        return new TurnAttempt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1, UUID.randomUUID());
    }

    private TurnContextLoads.Load load(TurnAttempt turn, Instant deadline, String path) {
        var refused = folder.admit(turn);
        return refused.isPresent() ? refused.get() : folder.prepare(turn, deadline, path);
    }

    @Test
    void shouldArchiveTheContextBuildWithItsIndexAndFieldGuide() throws IOException {
        beans.registerSingleton("loader", new Loader(temp));
        Map<String, byte[]> built = Map.of("inputs/context/user.json", "{}".getBytes(StandardCharsets.UTF_8));
        Map<String, String> archived = entries(new ByteArrayInputStream(folder.documentsArchive(turn(), built, "now")));
        assertThat(archived).containsEntry("inputs/context/user.json", "{}");
        assertThat(archived.get("INDEX.md")).contains("inputs/context/user.json", "repos/");
        assertThat(archived.get("FIELDS.md")).isNotBlank();
    }

    @Test
    void shouldAnswerTheLoadedItemAsAnArchiveWithItsVersionSizeAndSha256() throws Exception {
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        Files.writeString(checkout.resolve("README.md"), "# API\n");
        beans.registerSingleton("loader", new Loader(checkout));
        TurnAttempt turn = turn();

        TurnContextLoads.Load load = load(turn, DEADLINE, "repos/github.com/acme/api@main");

        assertThat(load).isInstanceOf(TurnContextLoads.Prepared.class);
        TurnContextLoads.Prepared prepared = (TurnContextLoads.Prepared) load;
        try {
            assertThat(prepared.answer().path("path").asString()).isEqualTo("repos/github.com/acme/api");
            assertThat(prepared.answer().path("resolved").path("cite").asString())
                    .isEqualTo("@abcdef1");
            assertThat(prepared.answer().path("note").asString()).isEqualTo("Loaded.");
            assertThat(prepared.answer().path("summary").values()).hasSize(4);
            assertThat(captures).hasValue(1);
            var bytes = new ByteArrayOutputStream();
            prepared.writeTo(bytes);
            byte[] archive = bytes.toByteArray();
            assertThat(prepared.size()).isEqualTo(archive.length);
            assertThat(prepared.sha256())
                    .isEqualTo(HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(archive)));
            assertThat(entries(new ByteArrayInputStream(archive))).containsExactly(Map.entry("README.md", "# API\n"));
        } finally {
            prepared.close();
        }
        assertThat(prepared.source().directory()).doesNotExist();
        assertThat(captures).hasValue(0);
        assertThat(refusal(load(turn, DEADLINE, "repos/github.com/acme/hidden")))
                .isEqualTo("NOT_VISIBLE");
        assertThat(refusal(load(turn, DEADLINE, "elsewhere/x"))).isEqualTo("INVALID_PATH");
    }

    @Test
    void shouldReproduceTheMeasuredTarWithoutKeepingADuplicateArchive() throws Exception {
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        Path nested = Files.createDirectories(checkout.resolve("long-segment-".repeat(12)));
        Files.writeString(nested.resolve("unicode-ä.md"), "long path");
        Files.writeString(checkout.resolve("z.md"), "last");
        Files.writeString(checkout.resolve("a.md"), "first");
        beans.registerSingleton("loader", new Loader(checkout));
        TurnContextLoads.Load load = load(turn(), DEADLINE, "repos/github.com/acme/api");
        assertThat(load).isInstanceOf(TurnContextLoads.Prepared.class);
        try (var prepared = (TurnContextLoads.Prepared) load) {
            var first = new ByteArrayOutputStream();
            prepared.writeTo(first);
            Files.setLastModifiedTime(
                    prepared.source().directory().resolve("a.md"), FileTime.from(NOW.plusSeconds(30)));
            var second = new ByteArrayOutputStream();
            prepared.writeTo(second);
            assertThat(first.toByteArray()).isEqualTo(second.toByteArray());
            assertThat(second.size()).isEqualTo(prepared.size());
            assertThat(HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(second.toByteArray())))
                    .isEqualTo(prepared.sha256());
            assertThat(captures).hasValue(1);
        }
        assertThat(captures).hasValue(0);
    }

    @Test
    void shouldKeepCaptureAdmissionUntilFailedSourceDeletionIsRetried() throws IOException {
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        Files.writeString(checkout.resolve("private.md"), "personal data");
        Loader loader = new Loader(checkout);
        beans.registerSingleton("loader", loader);
        TurnContextLoads.Load load = load(turn(), DEADLINE, "repos/github.com/acme/api");
        assertThat(load).isInstanceOf(TurnContextLoads.Prepared.class);
        var prepared = (TurnContextLoads.Prepared) load;
        loader.refusesClose.set(true);
        try {
            assertThatThrownBy(prepared::close).isInstanceOf(UncheckedIOException.class);
            assertThat(prepared.source().directory().resolve("private.md")).exists();
            assertThat(captures)
                    .as("an erasure cannot pass a remaining physical copy")
                    .hasValue(1);
        } finally {
            loader.refusesClose.set(false);
            prepared.close();
        }
        assertThat(prepared.source().directory()).doesNotExist();
        assertThat(captures).hasValue(0);
    }

    @Test
    void shouldRefuseTheSixthLoadOfAnAttempt() throws IOException {
        Path checkout = Files.createDirectories(temp.resolve("checkout"));
        beans.registerSingleton("loader", new Loader(checkout));
        TurnAttempt turn = turn();

        for (int load = 0; load < TurnContextLoads.MAX_LOADS_PER_ATTEMPT; load++) {
            TurnContextLoads.Load loaded = load(turn, DEADLINE, "repos/github.com/acme/api");
            assertThat(loaded).isInstanceOf(TurnContextLoads.Prepared.class);
            ((TurnContextLoads.Prepared) loaded).close();
        }

        assertThat(refusal(load(turn, DEADLINE, "repos/github.com/acme/api"))).isEqualTo("BUDGET_EXHAUSTED");
    }

    @Test
    void shouldAcquireNoCaptureSessionWhileAContextLoadWaitsForAPermit() throws Exception {
        var entered = new CountDownLatch(TurnContextLoads.LOADS_AT_ONCE);
        var release = new CountDownLatch(1);
        beans.registerSingleton("loader", new Loader(temp) {
            @Override
            public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
                entered.countDown();
                waitFor(release);
                throw new ContextLoadException(ContextLoadException.Code.UNAVAILABLE, "fixture released");
            }
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> load(turn(), DEADLINE, "repos/github.com/acme/api"));
            var second = workers.submit(() -> load(turn(), DEADLINE, "repos/github.com/acme/api"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(refusal(load(turn(), NOW.plusMillis(100), "repos/github.com/acme/api")))
                        .isEqualTo("TIMEOUT");
                assertThat(captures)
                        .as("only the two active readers own database sessions")
                        .hasValue(2);
                verify(copies, times(2)).capture();
            } finally {
                release.countDown();
            }
            assertThat(refusal(first.get(5, TimeUnit.SECONDS))).isEqualTo("UNAVAILABLE");
            assertThat(refusal(second.get(5, TimeUnit.SECONDS))).isEqualTo("UNAVAILABLE");
        }
        assertThat(captures).hasValue(0);
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

        TurnContextLoads.Load ended = load(turn, NOW.plusMillis(300), "repos/github.com/acme/api");

        assertThat(refusal(ended)).isEqualTo("TIMEOUT");
        assertThat(folder.loadsRunning())
                .as("the fetch still runs and keeps its permit")
                .isOne();
        assertThat(captures)
                .as("the abandoned loader still retains capture admission")
                .hasValue(1);
        fetch.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> folder.loadsRunning() == 0 && captures.get() == 0);
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

        assertThat(refusal(load(turn, DEADLINE, "repos/github.com/acme/api"))).isEqualTo("UNAVAILABLE");
    }

    private static String refusal(TurnContextLoads.Load load) {
        assertThat(load).isInstanceOf(TurnContextLoads.Refused.class);
        return ((TurnContextLoads.Refused) load).answer().path("error").asString();
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
    private class Loader implements ContextLoader {
        private final Path directory;
        private final AtomicBoolean refusesClose = new AtomicBoolean();

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
            Path snapshot;
            try {
                snapshot = layout.spool().directory(workspaceId).path();
                try (var paths = Files.walk(directory)) {
                    for (Path file : paths.toList()) {
                        Path target =
                                snapshot.resolve(directory.relativize(file).toString());
                        if (Files.isDirectory(file)) Files.createDirectories(target);
                        else Files.copy(file, target);
                    }
                }
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
            return new Loaded() {
                @Override
                public String path() {
                    return "repos/github.com/acme/api";
                }

                @Override
                public Path directory() {
                    return snapshot;
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
                public void close() {
                    if (refusesClose.get()) throw new UncheckedIOException(new IOException("fixture deletion failed"));
                    try {
                        EvidenceSpool.close(snapshot);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }
            };
        }
    }
}
