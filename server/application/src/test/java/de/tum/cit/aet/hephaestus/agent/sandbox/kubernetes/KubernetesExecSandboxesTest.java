package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.fabric8.mockwebserver.MockWebServer;
import io.fabric8.mockwebserver.http.Dispatcher;
import io.fabric8.mockwebserver.http.MockResponse;
import io.fabric8.mockwebserver.http.RecordedRequest;
import io.fabric8.mockwebserver.http.Response;
import io.fabric8.mockwebserver.http.WebSocket;
import io.fabric8.mockwebserver.http.WebSocketListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocketFrame;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The turn sandbox's exec and stage, through the real Kubernetes client, against the client's own mock web server or,
 * for the two controls that need a WebSocket peer that stops reading or breaks the protocol, a Vert.x server. Each
 * answers the client's read of the turn Pod as the API server does: its list by name, then the Pod itself, both ready.
 * So each exec reaches its WebSocket upgrade before any delay or failure the test sets up.
 *
 * <p>The shared client is configured as in production: a 30 second request timeout and ten retries of a failed
 * request. It reads no kubeconfig and no service account; the only data is a synthetic Pod and a synthetic command.
 *
 * <p>These tests prove the client side only: that each call ends, by its budget or by the failure, whatever the transport
 * does, and what the adapter reports. Whether a process that the API server started still runs is the kubelet's, and outside them. That
 * a {@link SandboxException} makes the context folder remove the sandbox and stage a new one is
 * {@code TurnContextFolderTest}'s.
 */
@Tag("unit")
@Timeout(60)
class KubernetesExecSandboxesTest {

    private static final String TURNS = "hephaestus-sandboxes";
    private static final String SANDBOX = "hephaestus-turn-test";
    private static final String PODS_PATH = "/api/v1/namespaces/" + TURNS + "/pods";
    private static final String POD_PATH = PODS_PATH + "/" + SANDBOX;
    private static final String EXEC_PATH = POD_PATH + "/exec?";
    private static final List<String> ARGV = List.of("echo", "synthetic");
    private static final String SUCCESS = "{\"metadata\":{},\"status\":\"Success\"}";

    /**
     * A call that outlives this hangs, and {@link #ended} fails it there. It is far beyond the short budgets that a call
     * must end by. A test whose call must end by a failure instead sets a budget beyond it. It is below the shared
     * client's 30 second request timeout, so that the timeout cannot end a hanging call just in time.
     */
    private static final Duration HANG = Duration.ofSeconds(20);

    /** A budget beyond {@link #HANG}: a call that waits for it fails the test. */
    private static final Duration BEYOND_HANG = Duration.ofMinutes(5);

    /** The client's send queue: a write waits while this much is queued ({@code ExecWebSocketListener}). */
    private static final int SEND_QUEUE = 16 * 1024 * 1024;

    /** Larger than the client's send queue and the sockets' buffers together. */
    private static final int LARGE_ARCHIVE = 48 * 1024 * 1024;

    private final MockWebServer server = new MockWebServer();
    private final KubernetesSandboxCache quotaCache = mock(KubernetesSandboxCache.class);
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Function<RecordedRequest, MockResponse> exec = request -> new MockResponse().setResponseCode(404);
    private volatile boolean failReads;
    private @Nullable KubernetesClient client;

    @BeforeEach
    void start() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = Objects.toString(request.getPath(), "");
                paths.add(path);
                if (path.startsWith(EXEC_PATH)) {
                    return exec.apply(request);
                }
                if (failReads) {
                    return new MockResponse().setResponseCode(503);
                }
                if (path.startsWith(PODS_PATH + "?") && path.contains("watch=true")) {
                    // Nothing changes: the Pod is ready already.
                    return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {});
                }
                if (path.startsWith(PODS_PATH + "?")) {
                    return json(podList());
                }
                if (path.equals(POD_PATH)) {
                    return json(new KubernetesSerialization().asJson(runningPod()));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        released.countDown();
        if (client != null) {
            client.close();
        }
        server.close();
    }

    @Test
    void shouldWaitFromTheQuotaCacheWithoutSendingRefusedPodCreates() {
        when(quotaCache.current()).thenReturn(true);
        when(quotaCache.quotas())
                .thenReturn(List.of(new ResourceQuotaBuilder()
                        .withNewStatus()
                        .addToHard("requests.memory", new Quantity("160Mi"))
                        .addToUsed("requests.memory", new Quantity("160Mi"))
                        .endStatus()
                        .build()));
        try (var runtime = sandboxes(server.url("/").toString())) {
            assertThatThrownBy(() -> runtime.create("tools", TurnSandboxes.LIMITS))
                    .isInstanceOfSatisfying(
                            SandboxCapacityException.class,
                            full -> assertThat(full.reason())
                                    .isEqualTo(SandboxCapacityException.Reason.QUOTA_EXCEEDED));
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void shouldRefuseAQuotaTooSmallForOneToolsPodWithoutWaiting() {
        when(quotaCache.current()).thenReturn(true);
        when(quotaCache.quotas())
                .thenReturn(List.of(new ResourceQuotaBuilder()
                        .withNewStatus()
                        .addToHard("requests.memory", new Quantity("128Mi"))
                        .endStatus()
                        .build()));
        try (var runtime = sandboxes(server.url("/").toString())) {
            assertThatThrownBy(() -> runtime.create("tools", TurnSandboxes.LIMITS))
                    .isInstanceOfSatisfying(
                            SandboxCapacityException.class,
                            full -> assertThat(full.reason())
                                    .isEqualTo(SandboxCapacityException.Reason.QUOTA_CANNOT_FIT));
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void shouldReportAQuota403AsCapacity() {
        assertThat(refusedCreate("pods tools is forbidden: exceeded quota: conversations"))
                .isInstanceOf(SandboxCapacityException.class);
    }

    @Test
    void shouldKeepAPermission403SeparateFromCapacity() {
        assertThat(refusedCreate("pods tools is forbidden: service account may not create pods"))
                .isInstanceOf(SandboxException.class)
                .isNotInstanceOf(SandboxCapacityException.class);
    }

    private Throwable refusedCreate(String message) {
        try (var runtime = sandboxes(server.url("/").toString())) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return json(new KubernetesSerialization()
                                    .asJson(new StatusBuilder()
                                            .withCode(403)
                                            .withMessage(message)
                                            .build()))
                            .setResponseCode(403);
                }
            });
            return catchThrowable(() -> runtime.create("tools", TurnSandboxes.LIMITS));
        }
    }

    @Test
    void shouldReturnTheCommandsOutputAndExitAndLeaveTheSharedClientOpenWhenCommandsRun() {
        exec = request -> new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(channel(1, "ran"));
                webSocket.send(channel(3, SUCCESS));
            }
        });
        var sandboxes = sandboxes(server.url("/").toString());

        // A few calls in a row on the shared client.
        for (int call = 0; call < 5; call++) {
            ExecSandboxes.Execution run = sandboxes.exec(SANDBOX, command(Duration.ofSeconds(10)));

            assertThat(run.exitCode()).isZero();
            assertThat(new String(run.stdout(), UTF_8)).isEqualTo("ran");
        }
        assertThat(paths).anyMatch(path -> path.startsWith(EXEC_PATH));
        assertThat(shared().getHttpClient().isClosed())
                .as("a call closes nothing of the shared client")
                .isFalse();
    }

    @Test
    void shouldDrainABurstBeforeThePeerClosesWithoutLosingTheExitCode() {
        String chunk = "x".repeat(8192);
        exec = request -> new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                for (int i = 0; i < 64; i++) {
                    webSocket.send(channel(1, chunk));
                }
                webSocket.send(channel(3, SUCCESS));
                webSocket.close(1000, "Complete");
            }
        });
        var sandboxes = sandboxes(server.url("/").toString());

        ExecSandboxes.Execution run = sandboxes.exec(SANDBOX, command(Duration.ofSeconds(30)));

        assertThat(run.exitCode()).isZero();
        assertThat(new String(run.stdout(), UTF_8)).isEqualTo("x".repeat(1024));
        assertThat(run.truncated()).isTrue();
        assertThat(run.stderr()).isEmpty();
    }

    @Test
    void shouldNotReplayAnExecWhoseConnectionClosesDuringTheUpgrade() throws Exception {
        withVertx(vertx -> {
            AtomicLong upgrades = new AtomicLong();
            String pod = new KubernetesSerialization().asJson(runningPod());
            int port = vertx.createHttpServer()
                    .requestHandler(request -> {
                        if ((POD_PATH + "/exec").equals(request.path())) {
                            upgrades.incrementAndGet();
                            request.connection().close();
                        } else {
                            request.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(pod);
                        }
                    })
                    .listen(0, "127.0.0.1")
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
                    .actualPort();
            var sandboxes = sandboxes("http://127.0.0.1:" + port);
            // A pooled connection can fail after the API has already received the exec request.
            shared().pods().inNamespace(TURNS).withName(SANDBOX).get();

            assertThatThrownBy(() -> sandboxes.exec(SANDBOX, command(Duration.ofSeconds(10))))
                    .isInstanceOf(SandboxException.class);

            assertThat(upgrades).hasValue(1);
        });
    }

    @Test
    void shouldStartNoRequestWhenTheCommandHasNoTimeLeft() {
        ExecSandboxes.Execution run = sandboxes(server.url("/").toString()).exec(SANDBOX, command(Duration.ZERO));

        assertThat(run.timedOut()).isTrue();
        assertThat(server.getRequestCount()).isZero();
    }

    /** The status the API server sends when no process of the sandbox user was left: {@code kill}'s exit 1. */
    @Test
    void shouldConfirmACancellationWhoseKillFoundNoProcess() {
        answerExecWith("{\"metadata\":{},\"status\":\"Failure\",\"reason\":\"NonZeroExitCode\","
                + "\"details\":{\"causes\":[{\"reason\":\"ExitCode\",\"message\":\"1\"}]}}");
        var sandboxes = sandboxes(server.url("/").toString());

        sandboxes.cancelCommands(SANDBOX);

        assertThat(paths).anyMatch(path -> path.startsWith(EXEC_PATH));
    }

    /**
     * A failure status without an exit code, as when the runtime could not start the exec process: the kill never ran,
     * and the client reads it as -1.
     */
    @Test
    void shouldNotConfirmACancellationWhoseKillDidNotRun() {
        answerExecWith(
                "{\"metadata\":{},\"status\":\"Failure\",\"reason\":\"InternalError\",\"message\":\"synthetic\"}");
        var sandboxes = sandboxes(server.url("/").toString());

        assertThatThrownBy(() -> sandboxes.cancelCommands(SANDBOX))
                .isInstanceOf(SandboxException.class)
                .as("the client parsed the failure status, not a missing exit")
                .hasMessageContaining("exit -1");
        assertThat(paths).anyMatch(path -> path.startsWith(EXEC_PATH));
    }

    /** Answers the exec's upgrade with nothing but the given status on the exec protocol's status channel. */
    private void answerExecWith(String status) {
        exec = request -> new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(channel(3, status));
            }
        });
    }

    /**
     * The upgrade reaches the server, which answers it only after the call ended. The call cannot know whether the
     * command started, so it fails at its deadline; the exec that the server opens late is closed as it opens, and the
     * shared client still works.
     */
    @Test
    void shouldFailAtItsDeadlineAndKeepALateUpgradeFromItsCallWhenTheUpgradeIsAnsweredLate() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        exec = request -> {
            asked.countDown();
            await(released);
            return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    opened.countDown();
                    webSocket.send(channel(1, "late"));
                    webSocket.send(channel(3, SUCCESS));
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    closed.countDown();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable error, @Nullable Response response) {
                    closed.countDown();
                }
            });
        };
        Duration budget = Duration.ofMillis(1500);
        var sandboxes = sandboxes(server.url("/").toString());

        Ended call = ended(() -> sandboxes.exec(SANDBOX, command(budget)));

        assertThat(asked.getCount()).as("the upgrade reached the server").isZero();
        assertThat(released.getCount())
                .as("the deadline ended the call: the server still holds its answer")
                .isOne();
        assertThat(call.failure())
                .as("a command that did not open by its deadline may still start: not a timeout")
                .isInstanceOf(SandboxException.class);
        assertThat(call.interrupted()).isFalse();

        released.countDown();
        boolean openedLate = opened.await(3, TimeUnit.SECONDS);
        assertThat(!openedLate || closed.await(3, TimeUnit.SECONDS))
                .as("an upgrade answered after the call ended is closed as it opens")
                .isTrue();
        Pod read = shared().pods().inNamespace(TURNS).withName(SANDBOX).get();
        assertThat(read).as("the shared client still reads the Pod").isNotNull();
        assertThat(shared().getHttpClient().isClosed()).isFalse();
    }

    @Test
    void shouldEndWithoutRetryingWhenTheApiAnswersEachReadWithAServerError() {
        failReads = true;
        Duration budget = Duration.ofSeconds(3);
        var sandboxes = sandboxes(server.url("/").toString());

        Ended call = ended(() -> sandboxes.exec(SANDBOX, command(budget)));

        assertThat(call.failure()).isInstanceOf(SandboxException.class);
        assertThat(server.getRequestCount())
                .as("a call's own requests are not retried; the shared client would retry each ten times")
                .isLessThan(5);
    }

    @Test
    void shouldKeepAnInterruptFromElsewhereAndEndTheCall() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        exec = request -> {
            asked.countDown();
            await(released);
            return new MockResponse().setResponseCode(404);
        };
        var sandboxes = sandboxes(server.url("/").toString());

        // On a thread of its own, which the fixture interrupts.
        Ended call = assertTimeoutPreemptively(HANG, () -> {
            Thread caller = Thread.currentThread();
            Thread interrupter = Thread.ofVirtual().start(() -> {
                await(asked);
                caller.interrupt();
            });
            Throwable failure = catchThrowable(() -> sandboxes.exec(SANDBOX, command(BEYOND_HANG)));
            boolean interrupted = Thread.interrupted();
            interrupter.join();
            return new Ended(failure, interrupted);
        });

        assertThat(call.failure()).isInstanceOf(SandboxException.class);
        assertThat(call.interrupted()).as("the caller's interrupt is still set").isTrue();
    }

    /**
     * The client's own input stream throws its unchecked exception once the WebSocket failed. The peer reads on and
     * answers the close, so its Vert.x closes with nothing left open.
     */
    @Test
    void shouldFailAStagingWithASandboxExceptionWhenTheClientsStreamFails() throws Exception {
        withVertx(vertx -> {
            AtomicBoolean broken = new AtomicBoolean();
            int port = apiServer(
                    vertx,
                    webSocket -> webSocket.handler(received -> {
                        if (broken.compareAndSet(false, true)) {
                            // After the archive's first bytes: a continuation frame outside a fragmented message breaks
                            // the
                            // WebSocket protocol.
                            webSocket.writeFrame(WebSocketFrame.continuationFrame(Buffer.buffer(new byte[] {1}), true));
                        }
                    }));
            var archive = new ByteArrayInputStream(new byte[LARGE_ARCHIVE]);
            var sandboxes = sandboxes("http://127.0.0.1:" + port);

            // The failure, not the budget, ends the call.
            Throwable failure = ended(() -> sandboxes.stage(
                            SANDBOX, new ExecSandboxes.Replace("repos", archive, LARGE_ARCHIVE, BEYOND_HANG)))
                    .failure();

            assertThat(broken)
                    .as("the peer received archive bytes before it broke the protocol")
                    .isTrue();
            assertThat(failure)
                    .as("the client's failure arrives as the adapter's exception, which its callers discard on")
                    .isInstanceOf(SandboxException.class);
            assertThat(archive.available())
                    .as("the staging began to send the archive")
                    .isLessThan(LARGE_ARCHIVE);
        });
    }

    /**
     * The peer stops reading, so the client's send queue fills and the staging's copy waits on it. The call stops
     * waiting at the deadline and ends the copy, and the staging fails as one that ran out of time, not as a failure of
     * the runtime; the caller's archive is read no more once the call returned.
     *
     * <p>The paused peer reads nothing, the close of the connection included. Once every assertion ran, it reads again
     * and closes its WebSocket, so its Vert.x closes with nothing left open; a failed assertion stays the test's
     * failure, the peer's close failing beside it is kept as a suppressed exception.
     */
    @Test
    void shouldEndAStagingThatWaitsOnAFullSendQueueAtItsDeadline() throws Exception {
        withVertx(vertx -> {
            CountDownLatch upgraded = new CountDownLatch(1);
            AtomicReference<@Nullable ServerWebSocket> peer = new AtomicReference<>();
            int port = apiServer(vertx, webSocket -> {
                webSocket.pause();
                peer.set(webSocket);
                upgraded.countDown();
            });
            try {
                var archive = new ByteArrayInputStream(new byte[LARGE_ARCHIVE]);
                Duration budget = Duration.ofSeconds(3);
                var sandboxes = sandboxes("http://127.0.0.1:" + port);

                Ended call = ended(() ->
                        sandboxes.stage(SANDBOX, new ExecSandboxes.Replace("repos", archive, LARGE_ARCHIVE, budget)));

                int left = archive.available();
                assertThat(upgraded.getCount())
                        .as("the exec reached a peer that stopped reading")
                        .isZero();
                assertThat(LARGE_ARCHIVE - left)
                        .as("the staging filled the client's send queue")
                        .isGreaterThanOrEqualTo(SEND_QUEUE);
                assertThat(call.failure())
                        .as("a staging that ran out of time, not a failure of the runtime")
                        .isExactlyInstanceOf(SandboxException.class)
                        .hasMessage("Staging into the turn sandbox timed out");
                assertReadNoMore(archive, left);
                assertThat(call.interrupted()).isFalse();
            } catch (Exception | Error bodyFailure) {
                try {
                    release(peer.get());
                } catch (Exception releasing) {
                    bodyFailure.addSuppressed(releasing);
                }
                throw bodyFailure;
            }
            release(peer.get());
        });
    }

    /**
     * The inverse of a confirmed staging: the peer stops reading and, once the staging has filled the client's send
     * queue, ends the WebSocket normally, with no exit status. Whether that end releases the client's wait on its queue
     * or not, it does not confirm the staging: the call fails, by its deadline at the latest, and leaves the archive
     * alone once it returned.
     */
    @Test
    void shouldFailAStagingWhosePeerEndsTheStreamNormallyWhileTheSendQueueIsFull() throws Exception {
        withVertx(vertx -> {
            CountDownLatch upgraded = new CountDownLatch(1);
            AtomicReference<@Nullable ServerWebSocket> peer = new AtomicReference<>();
            int port = apiServer(vertx, webSocket -> {
                webSocket.pause();
                peer.set(webSocket);
                upgraded.countDown();
            });
            try {
                var archive = new ByteArrayInputStream(new byte[LARGE_ARCHIVE]);
                Duration budget = Duration.ofSeconds(3);
                var sandboxes = sandboxes("http://127.0.0.1:" + port);
                AtomicBoolean filled = new AtomicBoolean();
                Thread closer = onceTheSendQueueIsFull(archive, budget, filled, () -> {
                    ServerWebSocket webSocket = peer.get();
                    if (webSocket != null) {
                        webSocket.close();
                    }
                });

                Ended call = ended(() ->
                        sandboxes.stage(SANDBOX, new ExecSandboxes.Replace("repos", archive, LARGE_ARCHIVE, budget)));
                stop(closer);

                int left = archive.available();
                assertThat(filled)
                        .as("fixture condition: the peer ended the stream only after the staging filled the send queue")
                        .isTrue();
                assertThat(upgraded.getCount())
                        .as("the exec reached a peer that stopped reading")
                        .isZero();
                assertThat(LARGE_ARCHIVE - left)
                        .as("the staging filled the client's send queue")
                        .isGreaterThanOrEqualTo(SEND_QUEUE);
                assertThat(call.failure())
                        .as("a staging whose delivery is unknown, never a success")
                        .isInstanceOf(SandboxException.class);
                assertReadNoMore(archive, left);
                assertThat(call.interrupted()).isFalse();
            } catch (Exception | Error bodyFailure) {
                try {
                    release(peer.get());
                } catch (Exception releasing) {
                    bodyFailure.addSuppressed(releasing);
                }
                throw bodyFailure;
            }
            release(peer.get());
        });
    }

    /**
     * An interrupt of the calling thread from elsewhere, while the staging's copy waits on a full send queue, ends the
     * call well before its budget. The interrupt is still set when the call returns, and the copy, which the call owns,
     * has ended: it reads the archive no more.
     */
    @Test
    void shouldKeepAnInterruptFromElsewhereWhileAStagingWaitsOnAFullSendQueue() throws Exception {
        withVertx(vertx -> {
            AtomicReference<@Nullable ServerWebSocket> peer = new AtomicReference<>();
            int port = apiServer(vertx, webSocket -> {
                webSocket.pause();
                peer.set(webSocket);
            });
            try {
                var archive = new ByteArrayInputStream(new byte[LARGE_ARCHIVE]);
                var sandboxes = sandboxes("http://127.0.0.1:" + port);
                AtomicBoolean filled = new AtomicBoolean();
                AtomicBoolean late = new AtomicBoolean();

                // On a thread of its own, which the fixture interrupts. The budget is beyond HANG: only the interrupt
                // ends the call in time.
                Ended call = assertTimeoutPreemptively(HANG, () -> {
                    Thread interrupter = onceTheSendQueueIsFull(
                            archive, Duration.ofSeconds(5), filled, Thread.currentThread()::interrupt);
                    Throwable failure = catchThrowable(() -> sandboxes.stage(
                            SANDBOX, new ExecSandboxes.Replace("repos", archive, LARGE_ARCHIVE, BEYOND_HANG)));
                    boolean interrupted = Thread.interrupted();
                    stop(interrupter);
                    // Also takes back an interrupt that came after the call, so that it cannot reach a later call.
                    late.set(Thread.interrupted());
                    return new Ended(failure, interrupted);
                });

                int left = archive.available();
                assertThat(filled)
                        .as("fixture condition: the interrupt came only after the staging filled the send queue")
                        .isTrue();
                assertThat(late)
                        .as("fixture condition: no interrupt came after the call returned")
                        .isFalse();
                assertThat(LARGE_ARCHIVE - left)
                        .as("the copy waited on a full send queue")
                        .isGreaterThanOrEqualTo(SEND_QUEUE);
                assertThat(call.failure())
                        .as("the interrupt, not another failure, ended the call")
                        .isInstanceOf(SandboxException.class)
                        .hasMessageContaining("Interrupted");
                assertThat(call.interrupted())
                        .as("the caller's interrupt is still set")
                        .isTrue();
                assertReadNoMore(archive, left);
            } catch (Exception | Error bodyFailure) {
                try {
                    release(peer.get());
                } catch (Exception releasing) {
                    bodyFailure.addSuppressed(releasing);
                }
                throw bodyFailure;
            }
            release(peer.get());
        });
    }

    /**
     * A peer that reads every byte and then reports success: the staging sends exactly the archive's size and
     * succeeds.
     */
    @Test
    void shouldSendTheWholeArchiveAndSucceedWhenThePeerReadsItAll() throws Exception {
        withVertx(vertx -> {
            // One byte past a whole number of the copy's buffers, so that the last write is a short one.
            int size = 4 * 1024 * 1024 + 1;
            AtomicLong received = new AtomicLong();
            int port = apiServer(
                    vertx,
                    webSocket -> webSocket.binaryMessageHandler(message -> {
                        // Each message is the stdin channel's number, then its data.
                        if (received.addAndGet(message.length() - 1L) == size) {
                            webSocket.writeBinaryMessage(Buffer.buffer(channel(3, SUCCESS)));
                        }
                    }));
            var archive = new ByteArrayInputStream(new byte[size]);
            var sandboxes = sandboxes("http://127.0.0.1:" + port);

            sandboxes.stage(SANDBOX, new ExecSandboxes.Replace("repos", archive, size, Duration.ofSeconds(20)));

            assertThat(received.get())
                    .as("the peer received exactly the archive")
                    .isEqualTo(size);
            assertThat(archive.available()).isZero();
        });
    }

    /** Lets a paused peer read again, the close of the connection included, and closes its WebSocket. */
    private static void release(@Nullable ServerWebSocket peer) throws Exception {
        if (peer == null || peer.isClosed()) {
            return;
        }
        peer.resume();
        peer.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A test body on a Vert.x of its own. */
    @FunctionalInterface
    private interface OnVertx {
        void run(Vertx vertx) throws Exception;
    }

    /**
     * Runs the body on a Vert.x of its own and closes it after. A failure to close never replaces the body's own
     * failure: it is kept as a suppressed exception of it.
     */
    private static void withVertx(OnVertx body) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            body.run(vertx);
        } catch (Exception | Error failure) {
            try {
                close(vertx);
            } catch (Exception closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
        close(vertx);
    }

    private static void close(Vertx vertx) throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A Vert.x server that answers the Pod's list and read as the API server does, and an exec upgrade with its peer. */
    private static int apiServer(Vertx vertx, Handler<ServerWebSocket> peer) throws Exception {
        String pod = new KubernetesSerialization().asJson(runningPod());
        String list = podList();
        // The peer's own policy, not the producer's budget: a WebSocket that this server closes closes its connection a
        // second after its close frame, also when the client's close never arrives.
        HttpServer api = vertx.createHttpServer(new HttpServerOptions()
                        .setWebSocketSubProtocols(List.of("v4.channel.k8s.io"))
                        .setWebSocketClosingTimeout(1))
                .requestHandler(request -> {
                    String query = request.query();
                    boolean listed = PODS_PATH.equals(request.path()) && query != null && !query.contains("watch");
                    boolean read = POD_PATH.equals(request.path());
                    if (listed || read) {
                        request.response()
                                .putHeader("Content-Type", "application/json")
                                .end(listed ? list : pod);
                    } else {
                        request.response().setStatusCode(404).end();
                    }
                })
                .webSocketHandler(peer);
        return api.listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .actualPort();
    }

    private KubernetesExecSandboxes sandboxes(String url) {
        Config config = new ConfigBuilder(Config.empty())
                .withMasterUrl(url)
                .withRequestTimeout(30_000)
                .withRequestRetryBackoffLimit(10)
                .withRequestRetryBackoffInterval(100)
                .build();
        KubernetesClient shared = KubernetesSandboxConfiguration.clientBuilder()
                .withConfig(config)
                .build();
        client = shared;
        var worker = KubernetesFixtures.worker(TURNS);
        worker.proved(KubernetesSandboxRuntimeTest.passed());
        var pods = new KubernetesPods(shared, worker, 10, ImagePullPolicy.IF_NOT_PRESENT);
        return new KubernetesExecSandboxes(
                pods,
                worker,
                "agent",
                KubernetesFixtures.live(KubernetesFixtures.WORKER_ID),
                new SandboxExits(pods, new SimpleMeterRegistry()),
                quotaCache);
    }

    private KubernetesClient shared() {
        return Objects.requireNonNull(client, "a test builds the shared client first");
    }

    private static ExecSandboxes.Command command(Duration timeout) {
        return new ExecSandboxes.Command(ARGV, timeout, 1024);
    }

    /** How a call ended: what it threw, and whether its thread's interrupt was set when it returned. */
    private record Ended(@Nullable Throwable failure, boolean interrupted) {}

    /** Runs the call on a thread of its own, and fails the test there if the call outlives {@link #HANG}. */
    private static Ended ended(ThrowingCallable call) {
        return assertTimeoutPreemptively(HANG, () -> new Ended(catchThrowable(call), Thread.interrupted()));
    }

    /** Nothing reads the archive once the call returned: the copy that the call owns ended with it. */
    private static void assertReadNoMore(ByteArrayInputStream archive, int left) {
        Awaitility.await("nothing reads the archive after the call returned")
                .during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .until(() -> archive.available() == left);
    }

    /** Waits for a test's latch in the server's dispatch, at most as long as a test runs. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Starts a fixture thread that runs {@code action} once the staging has read at least the client's send queue from
     * the archive, and sets {@code filled} just before. If that does not occur within the given time, or the thread is
     * interrupted first, it returns without the action: the test then fails on {@code filled}, an unmet condition of
     * the fixture and no result of the code under test.
     */
    private static Thread onceTheSendQueueIsFull(
            ByteArrayInputStream archive, Duration within, AtomicBoolean filled, Runnable action) {
        return Thread.ofVirtual().start(() -> {
            try {
                Awaitility.await()
                        .atMost(within)
                        .pollInterval(Duration.ofMillis(10))
                        .until(() -> LARGE_ARCHIVE - archive.available() >= SEND_QUEUE);
            } catch (Exception unmet) {
                return;
            }
            filled.set(true);
            action.run();
        });
    }

    /** Ends a fixture thread and waits for it, so that it cannot act after its test. */
    private static void stop(Thread fixture) throws InterruptedException {
        fixture.interrupt();
        assertThat(fixture.join(Duration.ofSeconds(10)))
                .as("fixture condition: its thread ended")
                .isTrue();
    }

    /** A message on one channel of the Kubernetes exec protocol: the channel's number, then its data. */
    private static byte[] channel(int number, String data) {
        byte[] text = data.getBytes(UTF_8);
        byte[] message = new byte[text.length + 1];
        message[0] = (byte) number;
        System.arraycopy(text, 0, message, 1, text.length);
        return message;
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static String podList() {
        return new KubernetesSerialization()
                .asJson(new PodListBuilder()
                        .withNewMetadata()
                        .withResourceVersion("1")
                        .endMetadata()
                        .withItems(runningPod())
                        .build());
    }

    /** The turn Pod as the API server reports it once both containers run: ready, and each container ready. */
    private static Pod runningPod() {
        return new PodBuilder()
                .withNewMetadata()
                .withName(SANDBOX)
                .withNamespace(TURNS)
                .withResourceVersion("1")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(KubernetesPods.SANDBOX)
                .withImage("agent")
                .endContainer()
                .addNewContainer()
                .withName(KubernetesPods.STAGER)
                .withImage("agent")
                .endContainer()
                .endSpec()
                .withNewStatus()
                .withPhase("Running")
                .addNewCondition()
                .withType("Ready")
                .withStatus("True")
                .endCondition()
                .addNewCondition()
                .withType("ContainersReady")
                .withStatus("True")
                .endCondition()
                .withContainerStatuses(running(KubernetesPods.SANDBOX), running(KubernetesPods.STAGER))
                .endStatus()
                .build();
    }

    private static ContainerStatus running(String container) {
        return new ContainerStatusBuilder()
                .withName(container)
                .withReady(true)
                .withNewState()
                .withNewRunning()
                .endRunning()
                .endState()
                .build();
    }
}
