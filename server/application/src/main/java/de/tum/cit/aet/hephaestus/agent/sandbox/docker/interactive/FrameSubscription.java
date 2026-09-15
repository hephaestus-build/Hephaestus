package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import io.micrometer.core.instrument.Counter;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

/**
 * Per-subscriber bounded queue + virtual-thread dispatcher. Slow listeners drop their own frames
 * (drop-oldest); a throwing listener is logged once and downgraded — the pump is never affected.
 */
final class FrameSubscription implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(FrameSubscription.class);

    // Identity-checked sentinel to wake a blocked queue.take() on dispose.
    private static final JsonNode TERMINAL_SENTINEL = NullNode.getInstance();

    private static final long DISPATCHER_JOIN_TIMEOUT_MS = 250L;

    private final UUID subscriptionId = UUID.randomUUID();
    private final Consumer<JsonNode> listener;
    private final BlockingQueue<JsonNode> queue;
    private final Counter droppedCounter;
    private final Counter errorCounter;
    private final Runnable onDispose;
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean errorLoggedAtWarn = new AtomicBoolean(false);
    private volatile @Nullable Thread dispatcherThread;

    FrameSubscription(
            Consumer<JsonNode> listener,
            int queueCapacity,
            Counter droppedCounter,
            Counter errorCounter,
            Runnable onDispose) {
        this.listener = listener;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.droppedCounter = droppedCounter;
        this.errorCounter = errorCounter;
        this.onDispose = onDispose;
    }

    void start() {
        Thread t = Thread.ofVirtual()
                .name("mentor-sub-" + subscriptionId)
                .uncaughtExceptionHandler((thread, ex) -> log.warn("Subscriber dispatcher died unexpectedly", ex))
                .start(this::dispatchLoop);
        this.dispatcherThread = t;
    }

    /** Drop-oldest on overflow. No-op when disposed. */
    void offer(JsonNode frame) {
        if (disposed.get()) {
            return;
        }
        if (!queue.offer(frame)) {
            queue.poll();
            droppedCounter.increment();
            queue.offer(frame);
        }
    }

    @Override
    public boolean isDisposed() {
        return disposed.get();
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        queue.offer(TERMINAL_SENTINEL);
        try {
            onDispose.run();
        } catch (Exception e) {
            log.debug("onDispose callback failed for subscription {}: {}", subscriptionId, e.toString());
        }
        Thread t = dispatcherThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(DISPATCHER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void dispatchLoop() {
        while (!disposed.get()) {
            JsonNode frame;
            try {
                frame = queue.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame == TERMINAL_SENTINEL) {
                return;
            }
            try {
                listener.accept(frame);
            } catch (Throwable t) {
                errorCounter.increment();
                if (errorLoggedAtWarn.compareAndSet(false, true)) {
                    log.warn("Mentor subscriber listener threw — further errors at TRACE", t);
                } else if (log.isTraceEnabled()) {
                    log.trace("Mentor subscriber listener threw", t);
                }
            }
        }
    }
}
