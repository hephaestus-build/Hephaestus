package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;

/**
 * Per-subscriber bounded queue + virtual-thread dispatcher. Delivery never skips a frame: a full
 * queue blocks the producer (the pump, and through it the runner's stdout) until a deadline. A
 * subscriber that would otherwise miss a frame — its listener stalled past the deadline or threw, or
 * the frame never arrived intact — is cut off: disposed and told once through {@code onLost}, because a
 * stream with a gap in it is worse than no stream. When the stream itself ends ({@link #endOfStream}),
 * the subscriber first receives every frame already queued, then is told the same way.
 * {@link #dispose} is a deliberate cancellation and never reports a loss.
 */
final class FrameSubscription implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(FrameSubscription.class);

    private static final long DISPATCHER_JOIN_TIMEOUT_MS = 250L;

    /** A blocked producer, and an idle dispatcher, re-check disposal and end of stream this often. */
    private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final UUID subscriptionId = UUID.randomUUID();
    private final Consumer<JsonNode> listener;
    private final BlockingQueue<JsonNode> queue;
    private final Duration stallTimeout;
    private final Counter cutOffCounter;
    private final Counter errorCounter;
    private final Runnable onLost;
    private final Runnable onDispose;
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private volatile boolean streamEnded;
    private volatile @Nullable Thread dispatcherThread;

    /**
     * @param onLost runs once, on the producer's or dispatcher's thread, when this subscriber is cut
     *     off; must not block
     */
    FrameSubscription(
            Consumer<JsonNode> listener,
            int queueCapacity,
            Duration stallTimeout,
            Counter cutOffCounter,
            Counter errorCounter,
            Runnable onLost,
            Runnable onDispose) {
        this.listener = listener;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.stallTimeout = stallTimeout;
        this.cutOffCounter = cutOffCounter;
        this.errorCounter = errorCounter;
        this.onLost = onLost;
        this.onDispose = onDispose;
    }

    void start() {
        Thread t = Thread.ofVirtual()
                .name("mentor-sub-" + subscriptionId)
                .uncaughtExceptionHandler((thread, ex) -> log.warn("Subscriber dispatcher died unexpectedly", ex))
                .start(this::dispatchLoop);
        this.dispatcherThread = t;
    }

    void offer(JsonNode frame) {
        offer(frame, System.nanoTime() + stallTimeout.toNanos());
    }

    /**
     * Blocks while the queue is full; cuts the subscriber off if it is still full at {@code deadlineNanos}
     * ({@link System#nanoTime} scale). No-op when disposed or after the stream ended.
     */
    void offer(JsonNode frame, long deadlineNanos) {
        // A peer may have used up a shared deadline; only a full queue of our own is a stall.
        if (disposed.get() || streamEnded || queue.offer(frame)) {
            return;
        }
        try {
            while (!disposed.get()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    if (!disposed.get()) {
                        cutOffCounter.increment();
                    }
                    lose("its listener stalled with a full queue");
                    return;
                }
                if (queue.offer(frame, Math.min(remaining, SLICE_NANOS), TimeUnit.NANOSECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            lose("its producer was interrupted");
        }
    }

    /**
     * The runner's stream ended while this subscriber was still attached. It still receives the frames
     * already queued — a terminal frame among them is not lost — and is then cut off, unless its owner
     * disposes it first.
     */
    void endOfStream() {
        streamEnded = true;
    }

    /** Cuts this subscriber off because it cannot receive every frame. No-op when already disposed. */
    void lose(String reason) {
        // No join: the caller may be the pump, and the dispatcher it would wait for is the one stuck.
        if (!disposeOnce(false)) {
            return;
        }
        log.warn("Mentor subscriber {} cut off because {}", subscriptionId, reason);
        try {
            onLost.run();
        } catch (RuntimeException e) {
            log.warn("onLost callback failed for subscription {}", subscriptionId, e);
        }
    }

    @Override
    public boolean isDisposed() {
        return disposed.get();
    }

    @Override
    public void dispose() {
        disposeOnce(true);
    }

    /** @return whether this call disposed the subscription */
    private boolean disposeOnce(boolean awaitDispatcher) {
        if (!disposed.compareAndSet(false, true)) {
            return false;
        }
        queue.clear();
        try {
            onDispose.run();
        } catch (Exception e) {
            log.debug("onDispose callback failed for subscription {}: {}", subscriptionId, e.toString());
        }
        Thread t = dispatcherThread;
        if (awaitDispatcher && t != null && t != Thread.currentThread()) {
            try {
                t.join(DISPATCHER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    private void dispatchLoop() {
        while (!disposed.get()) {
            JsonNode frame;
            try {
                frame = queue.poll(SLICE_NANOS, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (disposed.get()) {
                return;
            }
            if (frame == null) {
                if (streamEnded && queue.isEmpty()) {
                    lose("the runner's stream ended while it was still attached");
                    return;
                }
                continue;
            }
            try {
                listener.accept(frame);
            } catch (Throwable t) {
                errorCounter.increment();
                log.warn("Mentor subscriber listener threw", t);
                lose("its listener failed on a frame");
                return;
            }
        }
    }
}
