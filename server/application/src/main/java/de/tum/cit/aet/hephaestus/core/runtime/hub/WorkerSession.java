package de.tum.cit.aet.hephaestus.core.runtime.hub;

import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameCodec;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.FrameEnvelope;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.WorkerControlFrame;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

public final class WorkerSession {

    private static final Logger log = LoggerFactory.getLogger(WorkerSession.class);

    private final String workerId;
    private final String sessionId;
    private final String jti;
    private final Instant jwtExpiresAt;
    private final Instant connectedAt;
    private final WebSocketSession transport;
    private final FrameCodec codec;
    private final Object sendLock = new Object();
    private final AtomicReference<@Nullable CapacityReport> lastCapacity = new AtomicReference<>();
    private final AtomicBoolean forceReconnectSent = new AtomicBoolean(false);
    private final AtomicReference<@Nullable ScheduledFuture<?>> helloDeadline = new AtomicReference<>();
    private volatile Instant lastInboundAt;
    private volatile boolean draining;

    public WorkerSession(
            String workerId,
            String sessionId,
            String jti,
            Instant jwtExpiresAt,
            WebSocketSession transport,
            FrameCodec codec) {
        this.workerId = workerId;
        this.sessionId = sessionId;
        this.jti = jti;
        this.jwtExpiresAt = jwtExpiresAt;
        this.connectedAt = Instant.now();
        this.lastInboundAt = this.connectedAt;
        this.transport = transport;
        this.codec = codec;
    }

    public String workerId() {
        return workerId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String jti() {
        return jti;
    }

    public Instant jwtExpiresAt() {
        return jwtExpiresAt;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public Instant lastInboundAt() {
        return lastInboundAt;
    }

    void markInbound() {
        this.lastInboundAt = Instant.now();
    }

    @Nullable
    public CapacityReport lastCapacity() {
        return lastCapacity.get();
    }

    void updateCapacity(CapacityReport report) {
        lastCapacity.set(report);
    }

    /** @return {@code true} the first time it's called per session; subsequent calls return {@code false}. */
    boolean markForceReconnectSent() {
        return forceReconnectSent.compareAndSet(false, true);
    }

    void armHelloDeadline(ScheduledFuture<?> future) {
        helloDeadline.set(future);
    }

    void cancelHelloDeadline() {
        ScheduledFuture<?> f = helloDeadline.getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }

    public boolean isDraining() {
        return draining;
    }

    void markDraining() {
        draining = true;
    }

    public boolean send(WorkerControlFrame frame) {
        FrameEnvelope envelope = FrameEnvelope.of(frame);
        String json;
        try {
            json = codec.encode(envelope);
        } catch (RuntimeException e) {
            log.warn(
                    "Frame encode failed for worker {}: {}",
                    workerId,
                    e.getClass().getSimpleName());
            return false;
        }
        synchronized (sendLock) {
            if (!transport.isOpen()) {
                return false;
            }
            try {
                transport.sendMessage(new TextMessage(json));
                return true;
            } catch (IOException | RuntimeException e) {
                log.warn(
                        "Frame send failed for worker {}: {}",
                        workerId,
                        e.getClass().getSimpleName());
                return false;
            }
        }
    }

    public void close(CloseStatus status) {
        try {
            transport.close(status);
        } catch (IOException e) {
            log.debug("Close failed for worker {}: {}", workerId, e.getClass().getSimpleName());
        }
    }

    public boolean isOpen() {
        return transport.isOpen();
    }
}
