package de.tum.cit.aet.hephaestus.core.runtime.hub;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtHandshakeInterceptor;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerSessionJwt;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CancelJob;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.ForceReconnect;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameCodec;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameEnvelope;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitAck;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitCancel;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOperation;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.GitOutput;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.Heartbeat;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerControlFrame;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerHello;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerWelcome;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class WorkerControlWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkerControlWebSocketHandler.class);
    static final String ATTR_WORKER_SESSION = "worker.session";

    private final WorkerSessionRegistry registry;
    private final FrameCodec codec;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService helloTimeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "worker-hub-hello-timeout");
        t.setDaemon(true);
        return t;
    });

    public WorkerControlWebSocketHandler(
            WorkerSessionRegistry registry, FrameCodec codec, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.codec = codec;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawTransport) {
        WorkerJwt jwt = (WorkerJwt) rawTransport.getAttributes().get(WorkerJwtHandshakeInterceptor.ATTR_JWT);
        if (jwt == null) {
            log.warn("WSS upgrade arrived without verified JWT attribute — closing.");
            close(rawTransport, CloseStatus.POLICY_VIOLATION);
            return;
        }
        rawTransport.setTextMessageSizeLimit(FrameCodec.MAX_FRAME_BYTES);
        rawTransport.setBinaryMessageSizeLimit(FrameCodec.MAX_FRAME_BYTES);
        // Wrap with a concurrent decorator: caps the per-session outbound buffer and a send
        // deadline, so a slow worker cannot balloon hub memory or hold a sender thread.
        WebSocketSession transport = new ConcurrentWebSocketSessionDecorator(
                rawTransport, (int) HubProperties.SEND_TIME_LIMIT.toMillis(), HubProperties.SEND_BUFFER_SIZE_BYTES);
        String sessionId = UUID.randomUUID().toString();
        if (!(jwt instanceof WorkerSessionJwt workerJwt)) {
            throw new IllegalStateException("job-scoped JWT cannot open a worker control session");
        }
        String workerId = workerJwt.workerId();
        WorkerSession session = new WorkerSession(workerId, sessionId, jwt.jti(), jwt.expiresAt(), transport, codec);
        rawTransport.getAttributes().put(ATTR_WORKER_SESSION, session);
        // Close half-open sessions that authenticate but never send WorkerHello.
        ScheduledFuture<?> helloDeadline = helloTimeoutScheduler.schedule(
                () -> {
                    if (!rawTransport.isOpen()) return;
                    log.warn("WorkerHello timeout for workerId={} sessionId={}; closing.", workerId, sessionId);
                    meterRegistry.counter(CoreMetrics.WORKER_HUB_HELLO_TIMEOUT).increment();
                    close(rawTransport, CloseStatus.SESSION_NOT_RELIABLE);
                },
                HubProperties.HELLO_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
        session.armHelloDeadline(helloDeadline);
        log.info(
                "WSS connection opened: workerId={}, sessionId={}, jwtExpiresAt={}",
                workerId,
                sessionId,
                jwt.expiresAt());
    }

    @PreDestroy
    void shutdown() {
        helloTimeoutScheduler.shutdownNow();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession transport, BinaryMessage message) {
        WorkerSession session = (WorkerSession) transport.getAttributes().get(ATTR_WORKER_SESSION);
        String workerId = session != null ? session.workerId() : "<unauthenticated>";
        log.warn("Refusing binary frame from workerId={}: text-only protocol", workerId);
        meterRegistry.counter(CoreMetrics.WORKER_HUB_BINARY_REFUSED).increment();
        close(transport, CloseStatus.NOT_ACCEPTABLE);
    }

    @Override
    protected void handleTextMessage(WebSocketSession transport, TextMessage message) {
        WorkerSession session = (WorkerSession) transport.getAttributes().get(ATTR_WORKER_SESSION);
        if (session == null) {
            close(transport, CloseStatus.SERVER_ERROR);
            return;
        }
        FrameEnvelope envelope;
        try {
            envelope = codec.decode(message.getPayload());
        } catch (RuntimeException e) {
            log.warn(
                    "Frame decode failed for workerId={}: {}",
                    session.workerId(),
                    e.getClass().getSimpleName());
            meterRegistry.counter(CoreMetrics.WORKER_HUB_FRAME_DECODE_FAILED).increment();
            close(transport, CloseStatus.BAD_DATA);
            return;
        }
        session.markInbound();
        WorkerControlFrame frame = envelope.payload();
        try {
            dispatch(session, frame);
            maybeForceReconnect(session);
        } catch (RuntimeException e) {
            log.error(
                    "Hub frame dispatch threw for workerId={}, frame={}",
                    session.workerId(),
                    frame.getClass().getSimpleName(),
                    e);
            meterRegistry.counter(CoreMetrics.WORKER_HUB_FRAME_DISPATCH_FAILED).increment();
        }
    }

    private java.util.function.BiConsumer<WorkerSession, GitOutput> gitOutputHandler = (session, output) -> {};

    public void setGitOutputHandler(java.util.function.BiConsumer<WorkerSession, GitOutput> handler) {
        this.gitOutputHandler = handler;
    }

    private void dispatch(WorkerSession session, WorkerControlFrame frame) {
        switch (frame) {
            case GitOutput output -> gitOutputHandler.accept(session, output);
            case GitOperation operation -> warnUnexpectedFrame(session, operation);
            case GitAck ack -> warnUnexpectedFrame(session, ack);
            case GitCancel cancel -> warnUnexpectedFrame(session, cancel);
            case WorkerHello hello -> handleHello(session, hello);
            case CapacityReport capacity -> {
                session.updateCapacity(capacity);
                // Echo a non-draining Heartbeat so the worker's silence-deadline (3× heartbeat
                // interval) doesn't trip a reconnect when the channel is otherwise idle.
                session.send(new Heartbeat(false));
            }
            case Heartbeat heartbeat -> {
                if (heartbeat.draining()) {
                    log.info("Worker {} signalled draining", session.workerId());
                    meterRegistry
                            .counter(CoreMetrics.WORKER_HUB_DRAINING_SIGNALLED)
                            .increment();
                }
            }
            case WorkerWelcome w -> warnUnexpectedFrame(session, w);
            case ForceReconnect f -> warnUnexpectedFrame(session, f);
            case CancelJob c -> warnUnexpectedFrame(session, c); // hub originates this; never inbound
        }
    }

    private void warnUnexpectedFrame(WorkerSession session, WorkerControlFrame frame) {
        log.warn(
                "Unexpected hub-bound frame {} from workerId={}",
                frame.getClass().getSimpleName(),
                session.workerId());
    }

    private void handleHello(WorkerSession session, WorkerHello hello) {
        session.cancelHelloDeadline();
        if (!hello.workerId().equals(session.workerId())) {
            log.warn(
                    "WorkerHello workerId={} does not match JWT subject {}; closing",
                    hello.workerId(),
                    session.workerId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        List<Integer> supported = hello.supportedVersions();
        if (!supported.contains(FrameEnvelope.CURRENT_VERSION)) {
            log.warn(
                    "WorkerHello from {} does not support protocol v{}; closing",
                    session.workerId(),
                    FrameEnvelope.CURRENT_VERSION);
            session.close(new CloseStatus(4400, "unsupported protocol version"));
            return;
        }
        registry.register(session);
        WorkerWelcome welcome = new WorkerWelcome(FrameEnvelope.CURRENT_VERSION, session.sessionId());
        session.send(welcome);
        meterRegistry.counter(CoreMetrics.WORKER_HUB_HANDSHAKE_COMPLETED).increment();
    }

    private void maybeForceReconnect(WorkerSession session) {
        Duration remaining = Duration.between(Instant.now(), session.jwtExpiresAt());
        if (!remaining.isNegative()
                && remaining.compareTo(HubProperties.FORCE_RECONNECT_THRESHOLD) <= 0
                && session.markForceReconnectSent()) {
            session.send(new ForceReconnect("jwt near expiry"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession transport, CloseStatus status) {
        WorkerSession session = (WorkerSession) transport.getAttributes().remove(ATTR_WORKER_SESSION);
        if (session != null) {
            registry.unregister(session, "ws-close:" + status.getCode());
            log.info(
                    "WSS connection closed: workerId={}, sessionId={}, status={}",
                    session.workerId(),
                    session.sessionId(),
                    status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession transport, Throwable exception) {
        WorkerSession session = (WorkerSession) transport.getAttributes().get(ATTR_WORKER_SESSION);
        String workerId = session != null ? session.workerId() : "<unauthenticated>";
        log.warn(
                "WSS transport error for workerId={}: {}",
                workerId,
                exception.getClass().getSimpleName());
        meterRegistry.counter(CoreMetrics.WORKER_HUB_TRANSPORT_ERRORS).increment();
    }

    private static void close(WebSocketSession transport, CloseStatus status) {
        try {
            transport.close(status);
        } catch (IOException ignored) {
            // close-during-close is fine
        }
    }
}
