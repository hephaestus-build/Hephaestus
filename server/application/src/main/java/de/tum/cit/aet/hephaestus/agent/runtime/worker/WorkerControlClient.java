package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CancelJob;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.ForceReconnect;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameCodec;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameEnvelope;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.Heartbeat;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerControlFrame;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerHello;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerWelcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * WSS control-channel client. Single outbound JDK {@link WebSocket} with exponential backoff and
 * silence-deadline reconnect. Two platform threads — outbound drain + inbound dispatch — so JDK
 * WebSocket callbacks (which pin virtual-thread carriers) don't OOM the carrier pool. Both
 * queues are bounded.
 */
public class WorkerControlClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerControlClient.class);
    private static final int OUTBOUND_QUEUE_CAPACITY = 1024;
    private static final int INBOUND_QUEUE_CAPACITY = 1024;
    private static final Duration MIN_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final WorkerProperties properties;
    private final FrameCodec codec;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Counter framesSent;
    private final Counter framesReceived;
    private final Counter sendDropped;
    private final Counter reconnects;

    private final LinkedBlockingQueue<FrameEnvelope> outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    private final LinkedBlockingQueue<WorkerControlFrame> inbound = new LinkedBlockingQueue<>(INBOUND_QUEUE_CAPACITY);
    private final AtomicReference<@Nullable WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastInboundAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<CountDownLatch> welcomeLatch = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Random random = new Random();

    private volatile @Nullable Thread outboundThread;
    private volatile @Nullable Thread inboundThread;
    private volatile @Nullable Thread connectionThread;

    /**
     * Handler for hub-originated {@link CancelJob} frames: {@code (jobId, reason)}. Wired by
     * {@code WorkerConfiguration} to the job executor's local-cancel path. Kept as a callback so the
     * transport layer stays decoupled from job execution.
     */
    private volatile @Nullable BiConsumer<UUID, String> cancelHandler;

    public WorkerControlClient(
            WorkerProperties properties, FrameCodec codec, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.framesSent = Counter.builder(AgentMetrics.WORKER_CONTROL_FRAMES_SENT)
                .description("WSS frames written to the hub")
                .register(meterRegistry);
        this.framesReceived = Counter.builder(AgentMetrics.WORKER_CONTROL_FRAMES_RECEIVED)
                .description("WSS frames decoded from the hub")
                .register(meterRegistry);
        this.sendDropped = Counter.builder(AgentMetrics.WORKER_CONTROL_FRAMES_DROPPED)
                .description("Outbound frames dropped (queue full or transport closed)")
                .register(meterRegistry);
        this.reconnects = Counter.builder(AgentMetrics.WORKER_CONTROL_RECONNECTS)
                .description("Reconnection attempts after a connect/handshake failure")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        this.outboundThread = newDaemon("worker-control-out", this::runOutboundLoop);
        this.inboundThread = newDaemon("worker-control-in", this::runInboundLoop);
        this.connectionThread = newDaemon("worker-control-fsm", this::runConnectionLoop);
        log.info(
                "Worker control client started; endpoint={}",
                properties.control().endpoint());
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            WebSocket ws = webSocket.getAndSet(null);
            if (ws != null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "worker shutdown");
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
        connected.set(false);
        interrupt(outboundThread);
        interrupt(inboundThread);
        interrupt(connectionThread);
    }

    public void send(WorkerControlFrame frame) {
        FrameEnvelope envelope = FrameEnvelope.of(frame);
        if (!outbound.offer(envelope)) {
            sendDropped.increment();
            log.warn("Outbound queue full; dropping frame {}", frame.getClass().getSimpleName());
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Register the handler invoked when the hub sends a {@link CancelJob}. Idempotent; the last
     * registration wins. {@code null} disables handling (frames are logged and dropped).
     */
    public void setCancelHandler(@Nullable BiConsumer<UUID, String> handler) {
        this.cancelHandler = handler;
    }

    /** Sentinel {@link Instant#EPOCH} when no frame has been received yet. */
    public Instant lastInboundAt() {
        return Objects.requireNonNull(lastInboundAt.get());
    }

    private void runOutboundLoop() {
        while (running.get()) {
            FrameEnvelope envelope;
            try {
                envelope = outbound.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            WebSocket ws = webSocket.get();
            if (ws == null || !connected.get()) {
                sendDropped.increment();
                continue;
            }
            try {
                String json = codec.encode(envelope);
                ws.sendText(json, true).toCompletableFuture().get(10, TimeUnit.SECONDS);
                framesSent.increment();
            } catch (Exception e) {
                log.warn("send failed: {} — closing connection", e.getClass().getSimpleName());
                forceReconnect("send-failure");
            }
        }
    }

    private void runInboundLoop() {
        while (running.get()) {
            WorkerControlFrame frame;
            try {
                frame = inbound.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            handleInbound(frame);
        }
    }

    void handleInbound(WorkerControlFrame frame) {
        try {
            switch (frame) {
                case WorkerWelcome welcome -> {
                    if (welcome.negotiatedVersion() != FrameEnvelope.CURRENT_VERSION) {
                        log.error(
                                "Hub negotiated unsupported protocol v{}; expected v{}",
                                welcome.negotiatedVersion(),
                                FrameEnvelope.CURRENT_VERSION);
                        forceReconnect("protocol-version-mismatch");
                        return;
                    }
                    connected.set(true);
                    CountDownLatch latch = welcomeLatch.get();
                    if (latch != null) latch.countDown();
                    log.info("Worker control channel connected; session={}", welcome.sessionId());
                }
                case ForceReconnect r -> {
                    log.info("Hub requested reconnect: {}", r.reason());
                    forceReconnect("server-requested:" + r.reason());
                }
                case CancelJob c -> handleCancelJob(c);
                // Empty on purpose. Arrival is the whole signal — see Heartbeat — and the transport
                // stamped lastInboundAt before dispatch, so there is nothing left to do here.
                case Heartbeat ignored -> {}
                // The hub never originates these — log and ignore (protocol violation by the hub).
                case WorkerHello h -> warnSourceMismatch(h);
                case CapacityReport r -> warnSourceMismatch(r);
            }
        } catch (RuntimeException e) {
            log.error("Inbound dispatch threw for {}", frame.getClass().getSimpleName(), e);
        }
    }

    private void handleCancelJob(CancelJob frame) {
        BiConsumer<UUID, String> handler = cancelHandler;
        if (handler == null) {
            log.debug("CancelJob received for {} but no cancel handler is wired; ignoring", frame.jobId());
            return;
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(frame.jobId());
        } catch (IllegalArgumentException e) {
            log.warn("CancelJob with malformed jobId '{}'; ignoring", frame.jobId());
            return;
        }
        handler.accept(jobId, frame.reason());
    }

    private void warnSourceMismatch(WorkerControlFrame frame) {
        log.warn("Unexpected worker-source frame from hub: {}", frame.getClass().getSimpleName());
    }

    private void runConnectionLoop() {
        Duration backoff = MIN_BACKOFF;
        boolean unconfiguredLogged = false;
        int failuresSinceSuccess = 0;
        while (running.get()) {
            try {
                if (!properties.control().isConfigured()) {
                    if (!unconfiguredLogged) {
                        log.warn("Worker control endpoint or registration token not configured "
                                + "(set HEPHAESTUS_HUB_URL + HEPHAESTUS_WORKER_REGISTRATION_TOKEN); "
                                + "the control channel will stay disconnected.");
                        unconfiguredLogged = true;
                    }
                    Thread.sleep(Duration.ofMinutes(5).toMillis());
                    continue;
                }
                String jwt = exchangeRegistrationToken();
                CountDownLatch latch = new CountDownLatch(1);
                welcomeLatch.set(latch);
                openWebSocket(jwt);
                if (!latch.await(properties.control().handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    forceReconnect("welcome-timeout");
                    throw new IOException("WorkerWelcome not received within "
                            + properties.control().handshakeTimeout());
                }
                backoff = MIN_BACKOFF;
                failuresSinceSuccess = 0;
                Duration silenceLimit = properties.heartbeat().interval().multipliedBy(3);
                while (running.get() && connected.get()) {
                    Thread.sleep(500);
                    Duration sinceInbound = Duration.between(lastInboundAt.get(), Instant.now());
                    if (sinceInbound.compareTo(silenceLimit) > 0) {
                        log.warn("Inbound silence exceeded {}; reconnecting", silenceLimit);
                        forceReconnect("inbound-silence");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                failuresSinceSuccess++;
                if (failuresSinceSuccess <= 3) {
                    log.warn(
                            "Control-channel attempt failed: {} ({})",
                            e.getClass().getSimpleName(),
                            e.getMessage());
                } else {
                    log.debug(
                            "Control-channel attempt failed (suppressed; failures={}): {}",
                            failuresSinceSuccess,
                            e.getClass().getSimpleName());
                }
            }
            if (!running.get()) {
                return;
            }
            backoff = nextBackoff(backoff);
            reconnects.increment();
            try {
                Thread.sleep(backoff.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Duration nextBackoff(Duration current) {
        long doubled = Math.min(current.toMillis() * 2, MAX_BACKOFF.toMillis());
        // ±20% jitter
        long jitter = (long) (doubled * 0.2 * (random.nextDouble() - 0.5));
        return Duration.ofMillis(Math.max(MIN_BACKOFF.toMillis(), doubled + jitter));
    }

    private String exchangeRegistrationToken() throws IOException, InterruptedException {
        URI endpoint = properties.control().endpoint();
        if (endpoint == null) throw new IOException("worker control endpoint is not configured");
        URI exchangeUri = URI.create(httpBaseFrom(endpoint) + "/api/workers/exchange");
        String workerId = properties.resolvedWorkerId();
        String body = objectMapper.writeValueAsString(Map.of(
                "workerId", workerId, "registrationToken", properties.control().registrationToken()));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(exchangeUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("token exchange failed: HTTP " + response.statusCode());
        }
        tools.jackson.databind.JsonNode json = objectMapper.readTree(response.body());
        tools.jackson.databind.JsonNode token = json.get("token");
        if (token == null
                || token.isNull()
                || !token.isString()
                || token.asString().isEmpty()) {
            throw new IOException("token exchange response missing token");
        }
        return token.asString();
    }

    private void openWebSocket(String jwt) throws InterruptedException, IOException {
        URI endpoint = properties.control().endpoint();
        if (endpoint == null) throw new IOException("worker control endpoint is not configured");
        try {
            WebSocket ws = httpClient
                    .newWebSocketBuilder()
                    .header("Authorization", "Bearer " + jwt)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(endpoint, new Listener())
                    .get(properties.control().handshakeTimeout().toSeconds(), TimeUnit.SECONDS);
            webSocket.set(ws);
            // Prime lastInboundAt so the silence-deadline check in runConnectionLoop doesn't
            // immediately trip on a freshly-opened socket (lastInboundAt starts at Instant.EPOCH).
            lastInboundAt.set(Instant.now());
            // Send WorkerHello immediately; connected flag flips on WorkerWelcome.
            String workerId = properties.resolvedWorkerId();
            FrameEnvelope hello =
                    FrameEnvelope.of(new WorkerHello(workerId, List.of(FrameEnvelope.CURRENT_VERSION), null));
            ws.sendText(codec.encode(hello), true).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException("WSS open failed: " + (cause == null ? e.getMessage() : cause.getMessage()), e);
        } catch (TimeoutException e) {
            throw new IOException("WSS open timed out", e);
        }
    }

    private void forceReconnect(String reason) {
        WebSocket ws = webSocket.getAndSet(null);
        connected.set(false);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
    }

    private static String httpBaseFrom(URI wsUri) {
        String scheme = "wss".equalsIgnoreCase(wsUri.getScheme()) ? "https" : "http";
        return scheme + "://" + wsUri.getHost() + (wsUri.getPort() == -1 ? "" : ":" + wsUri.getPort());
    }

    private static Thread newDaemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void interrupt(@Nullable Thread t) {
        if (t != null) {
            t.interrupt();
        }
    }

    /** JDK {@link WebSocket.Listener} that buffers inbound text into the dispatch queue. */
    private final class Listener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            webSocket.request(1);
            if (last) {
                String json = partial.toString();
                partial.setLength(0);
                framesReceived.increment();
                lastInboundAt.set(Instant.now());
                try {
                    FrameEnvelope envelope = codec.decode(json);
                    if (!inbound.offer(envelope.payload())) {
                        log.warn(
                                "Inbound queue full; dropping {}",
                                envelope.payload().getClass().getSimpleName());
                    }
                } catch (RuntimeException e) {
                    log.warn("Inbound frame decode failed: {}", e.getClass().getSimpleName());
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Worker control channel closed: code={}, reason={}", statusCode, reason);
            connected.set(false);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Worker control channel error: {}", error.getClass().getSimpleName());
            connected.set(false);
        }
    }
}
