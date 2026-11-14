package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextLoads;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.EvidenceSpool;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorTurnAccess;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.mock.web.MockHttpServletResponse;

/** The load request owns its retained source until response streaming ends, fails, or is cancelled. */
class TurnContextControllerTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @TempDir
    Path temp;

    private final PersonDataCopyFence copies = mock(PersonDataCopyFence.class);
    private final HephTurns turns = mock(HephTurns.class);
    private final MentorTurnAccess rows = mock(MentorTurnAccess.class);
    private final SandboxRun run = mock(SandboxRun.class);
    private final AtomicBoolean held = new AtomicBoolean();
    private final TurnAttempt attempt =
            new TurnAttempt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1, UUID.randomUUID());
    private TurnContextLoads loads;
    private TurnContextController controller;
    private Path source;

    @BeforeEach
    void prepareTheLoad() {
        var layout = new FabricLayout(temp.toString());
        source = temp.resolve("unallocated");
        when(copies.capture()).thenAnswer(unused -> {
            held.set(true);
            return new PersonDataCopyFence.Lease() {
                @Override
                public JdbcOperations jdbc() {
                    throw new UnsupportedOperationException("This fixture owns no JDBC connection");
                }

                @Override
                public void close() {
                    assertThat(source)
                            .as("source deletion precedes capture admission release")
                            .doesNotExist();
                    held.set(false);
                }
            };
        });
        when(turns.write(eq(run), eq(false), any())).thenAnswer(invocation -> {
            Function<TurnAttempt, Object> proposal = invocation.getArgument(2);
            return proposal.apply(attempt);
        });
        when(turns.workDeadline(run)).thenReturn(NOW.plusSeconds(300));
        when(rows.countContextLoad(anyLong(), any(), anyInt(), any(), anyInt())).thenReturn(true);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("loader", new ContextLoader() {
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
                assertThat(held).as("capture admission precedes source reads").isTrue();
                EvidenceSpool.Copy copy;
                try {
                    copy = layout.spool().directory(workspaceId);
                    source = copy.path();
                    Files.writeString(source.resolve("private.md"), "personal data");
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
                        return source;
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
                        assertThat(held).isTrue();
                        try {
                            copy.close();
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    }
                };
            }
        });
        loads = new TurnContextLoads(
                rows, beans.getBeanProvider(ContextLoader.class), Clock.fixed(NOW, ZoneOffset.UTC), copies, layout);
        controller =
                new TurnContextController(mock(HephTurnContexts.class), turns, loads, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stopPreparations() {
        loads.close();
    }

    @Test
    void shouldRetainCaptureUntilTheSynchronousResponseAndSourceCleanupFinish() throws Exception {
        var response = new MockHttpServletResponse();
        assertThat(controller
                        .load(run, new TurnContextController.LoadRequest("repos/fixture"), response)
                        .getCallable()
                        .call())
                .isNull();
        byte[] bytes = response.getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        assertThat(response.getContentLengthLong()).isEqualTo(bytes.length);
        assertThat(response.getHeader(TurnContextController.LOAD_SHA256_HEADER))
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(response.isCommitted()).isTrue();
        assertThat(source).doesNotExist();
        assertThat(held).isFalse();
    }

    @Test
    void shouldDeleteTheRetainedSourceWhenTheResponseCannotStart() {
        var response = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override
            public ServletOutputStream getOutputStream() throws IOException {
                throw new IOException("request cancelled before streaming started");
            }
        };
        assertThatThrownBy(() -> controller
                        .load(run, new TurnContextController.LoadRequest("repos/fixture"), response)
                        .getCallable()
                        .call())
                .isInstanceOf(IOException.class);
        assertThat(source).doesNotExist();
        assertThat(held).isFalse();
    }

    @Test
    void shouldDeleteTheRetainedSourceWhenTheClientDisconnectsDuringStreaming() {
        var response = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        throw new UnsupportedOperationException("The response is synchronous");
                    }

                    @Override
                    public void write(int value) throws IOException {
                        assertThat(held).isTrue();
                        assertThat(source.resolve("private.md")).exists();
                        throw new IOException("client disconnected while streaming");
                    }
                };
            }
        };
        assertThatThrownBy(() -> controller
                        .load(run, new TurnContextController.LoadRequest("repos/fixture"), response)
                        .getCallable()
                        .call())
                .isInstanceOf(IOException.class);
        assertThat(source).doesNotExist();
        assertThat(held).isFalse();
    }

    @Test
    void shouldReleaseTheRetainedSourceWhenTheResponseTaskIsCancelledAtItsWorkDeadline() throws Exception {
        var writing = new CountDownLatch(1);
        var response = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        throw new UnsupportedOperationException("blocking fixture");
                    }

                    @Override
                    public void write(int value) throws IOException {
                        writing.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("response deadline");
                        }
                    }
                };
            }
        };
        var task = controller.load(run, new TurnContextController.LoadRequest("repos/fixture"), response);
        assertThat(task.getTimeout()).isEqualTo(300_000L);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writingTask = executor.submit(task.getCallable());
            try {
                assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(held).isTrue();
            } finally {
                writingTask.cancel(true);
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> !held.get());
        }
        assertThat(source).doesNotExist();
    }
}
