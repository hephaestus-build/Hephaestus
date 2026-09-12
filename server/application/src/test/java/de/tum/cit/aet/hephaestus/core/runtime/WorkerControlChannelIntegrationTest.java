package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSession;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSessionRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerTokenDenylistService;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameCodec;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameEnvelope;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerHello;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end: a raw {@link java.net.http.WebSocket} client dials the running hub, presents an
 * issuer-minted JWT, completes the {@code WorkerHello}/{@code WorkerWelcome} handshake, and
 * publishes a {@code CapacityReport}. The hub's {@link WorkerSessionRegistry} sees the worker
 * exactly once, with the capacity recorded against it.
 *
 * <p>This exercises the full transport stack (Spring {@code WebSocketHandlerRegistry} + JWT
 * handshake interceptor + sealed-switch dispatch + per-session lifecycle events) against a
 * real booted context on the bound {@code @LocalServerPort}.
 */
class WorkerControlChannelIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    WorkerJwtIssuer jwtIssuer;

    @Autowired
    WorkerSessionRegistry workerSessions;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired(required = false)
    WorkerTokenDenylistService denylist;

    @Test
    void handshakeAndCapacityRoundTrip() throws Exception {
        String workerId = "worker-it-" + UUID.randomUUID();
        WorkerJwtIssuer.IssuedWorkerJwt jwt = jwtIssuer.issue(workerId);
        FrameCodec codec = new FrameCodec(objectMapper);

        CapturingListener listener = new CapturingListener();
        WebSocket ws = HttpClient.newBuilder()
                .build()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + jwt.token())
                .buildAsync(URI.create("ws://localhost:" + port + "/api/workers/connect"), listener)
                .get(10, TimeUnit.SECONDS);

        try {
            // Send WorkerHello.
            String helloJson = codec.encode(
                    FrameEnvelope.of(new WorkerHello(workerId, List.of(FrameEnvelope.CURRENT_VERSION), "0.0-it")));
            ws.sendText(helloJson, true).get(5, TimeUnit.SECONDS);

            // Hub responds with WorkerWelcome and registers the session.
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        assertThat(workerSessions.findByWorkerId(workerId))
                                .as("hub must register the worker after handshake")
                                .isPresent();
                    });

            // Publish a CapacityReport; verify the hub records it against the session.
            CapacityReport report = new CapacityReport(4, 2, 0, 0, 4, 2);
            String reportJson = codec.encode(FrameEnvelope.of(report));
            ws.sendText(reportJson, true).get(5, TimeUnit.SECONDS);

            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> {
                        Optional<WorkerSession> session = workerSessions.findByWorkerId(workerId);
                        assertThat(session).isPresent();
                        assertThat(session.get().lastCapacity()).isEqualTo(report);
                    });
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "test done").get(5, TimeUnit.SECONDS);
        }

        // After close, the session is gone from the registry.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() ->
                        assertThat(workerSessions.findByWorkerId(workerId)).isEmpty());
    }

    @Test
    void revokedJwtIsRejected() throws Exception {
        assertThat(denylist).as("WorkerTokenDenylistService must be wired").isNotNull();
        String workerId = "worker-rev-" + UUID.randomUUID();
        WorkerJwtIssuer.IssuedWorkerJwt jwt = jwtIssuer.issue(workerId);
        denylist.revoke(jwt.jti(), jwt.expiresAt());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            HttpClient.newBuilder()
                    .build()
                    .newWebSocketBuilder()
                    .header("Authorization", "Bearer " + jwt.token())
                    .buildAsync(URI.create("ws://localhost:" + port + "/api/workers/connect"), new CapturingListener())
                    .get(10, TimeUnit.SECONDS);
        } catch (Throwable t) {
            failure.set(t);
        }
        assertThat(failure.get())
                .as("revoked-JWT upgrade must fail; 401 manifests as a build-async error")
                .isNotNull();
    }

    private static final class CapturingListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
