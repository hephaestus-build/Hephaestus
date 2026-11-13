package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.EvidenceSpool;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** A real session capture lock covers loading, streaming, and deletion of this request's source files. */
@Tag("integration")
class TurnContextLoadCopyFenceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private PersonDataCopyFence copies;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path temp;

    @Test
    void shouldBlockErasureUntilTheLoadedSourceHasBeenStreamedAndDeleted() throws Exception {
        var layout = new FabricLayout(temp.toString());
        var source = new AtomicReference<Path>();
        var capturePid = new AtomicInteger();
        var observed = mock(PersonDataCopyFence.class);
        when(observed.capture()).thenAnswer(unused -> {
            var capture = copies.capture();
            capturePid.set(
                    Objects.requireNonNull(capture.jdbc().queryForObject("SELECT pg_backend_pid()", Integer.class)));
            return capture;
        });
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("loader", loader(layout, source, reading, release));
        Instant now = Instant.now();
        var turn = new TurnAttempt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1, UUID.randomUUID());
        try (var preparations = Executors.newVirtualThreadPerTaskExecutor();
                var folder = new TurnContextLoads(
                        mock(MentorTurnRepository.class),
                        beans.getBeanProvider(ContextLoader.class),
                        Clock.fixed(now, ZoneOffset.UTC),
                        observed,
                        layout)) {
            var loaded = preparations.submit(() -> folder.prepare(turn, now.plusSeconds(30), "repos/fixture"));
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                var erasure = preparations.submit(() -> {
                    try (var erased = copies.erase()) {
                        assertThat(erased.jdbc().queryForObject("""
                                SELECT EXISTS (
                                    SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid()
                                      AND locktype = 'advisory' AND classid = 2165 AND objid = 1
                                      AND mode = 'ExclusiveLock' AND granted)
                                """, Boolean.class))
                                .as("the worker's erasure lease owns the exclusive capture fence")
                                .isTrue();
                        assertThat(Objects.requireNonNull(source.get()))
                                .as("erasure admission follows deletion of this request's files")
                                .doesNotExist();
                        return true;
                    }
                });
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_locks WHERE locktype = 'advisory'
                              AND classid = 2165 AND objid = 1 AND NOT granted
                              AND ? = ANY(pg_blocking_pids(pid)))
                        """, Boolean.class, capturePid.get())));
                assertThat(erasure.isDone())
                        .as("erasure waits while the source is being read")
                        .isFalse();

                release.countDown();
                TurnContextLoads.Load load = loaded.get(5, TimeUnit.SECONDS);
                assertThat(load).isInstanceOf(TurnContextLoads.Prepared.class);
                try (var prepared = (TurnContextLoads.Prepared) load) {
                    assertThat(Objects.requireNonNull(source.get()).resolve("private.md"))
                            .exists();
                    var output = new ByteArrayOutputStream();
                    prepared.writeTo(output);
                    assertThat(output.size()).isEqualTo(prepared.size());
                    assertThat(erasure.isDone())
                            .as("stream completion alone does not release capture admission")
                            .isFalse();
                }
                assertThat(Objects.requireNonNull(source.get())).doesNotExist();
                assertThat(erasure.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
                TurnContextLoads.Load load = loaded.get(5, TimeUnit.SECONDS);
                if (load instanceof TurnContextLoads.Prepared prepared) prepared.close();
            }
        }
    }

    private static ContextLoader loader(
            FabricLayout layout, AtomicReference<Path> source, CountDownLatch reading, CountDownLatch release) {
        return new ContextLoader() {
            @Override
            public String prefix() {
                return "repos/";
            }

            @Override
            public String index(long workspaceId, long accountId) {
                return "repos/";
            }

            @Override
            public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
                EvidenceSpool.Copy copy;
                try {
                    copy = layout.spool().directory(workspaceId);
                    source.set(copy.path());
                    reading.countDown();
                    release.await();
                    Files.writeString(copy.path().resolve("private.md"), "personal data");
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(stopped);
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                }
                return new Loaded() {
                    @Override
                    public String path() {
                        return "repos/fixture";
                    }

                    @Override
                    public Path directory() {
                        return copy.path();
                    }

                    @Override
                    public Version version() {
                        return new Version("main", "abcdef1", "@abcdef1");
                    }

                    @Override
                    public String note() {
                        return "Loaded.";
                    }

                    @Override
                    public void close() {
                        try {
                            copy.close();
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    }
                };
            }
        };
    }
}
