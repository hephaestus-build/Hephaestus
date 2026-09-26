package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.ClientDisconnectedException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The webapp {@link MentorChannel} implementation: a per-turn façade over a single
 * {@link SseEmitter}. Owns everything that writes to the wire — chunk serialisation, comment-only
 * keep-alive pings, terminal {@code [DONE]} sentinel, and the "client gone" disconnect hook — so
 * {@link MentorChatService} can stay focused on orchestration and never branch on transport.
 * {@link #close()} is idempotent and called from the orchestrator's {@code finally} block.
 */
final class MentorSseChannel implements MentorChannel {

    private static final Logger log = LoggerFactory.getLogger(MentorSseChannel.class);

    /**
     * Emit an SSE comment-only keep-alive after this many ns of silence. ≤ 21 s worst-case
     * silence (initial-delay + quiet), safe under proxies that idle-close at 30 s+.
     */
    private static final long HEARTBEAT_QUIET_NS = TimeUnit.SECONDS.toNanos(20);

    private static final long HEARTBEAT_TICK_MS = 5_000;
    private static final long HEARTBEAT_INITIAL_DELAY_MS = 1_000;

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean clientGone = new AtomicBoolean(false);
    private final AtomicBoolean finishDelivered = new AtomicBoolean(false);
    /**
     * Set once {@link #completeWithDone}/{@link #completeWithError}/{@link #completeWithConflict}
     * runs. Distinguishes "we cleanly finished" from "client went away" — without it, a stray
     * post-complete {@code send()} (e.g. heartbeat tick mid-completion, or Pi emitting a second
     * {@code agent_end}) would throw {@link IllegalStateException} from Spring, get caught as
     * "disconnect", flip {@link #clientGone}, and fire the abort hook against a sandbox that
     * actually finished successfully.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicLong lastSendNanos = new AtomicLong(System.nanoTime());
    private final AtomicReference<@Nullable Runnable> disconnectHook = new AtomicReference<>();
    /**
     * Serialises every write to {@link #emitter} ({@link SseEmitter#send} is NOT thread-safe;
     * heartbeat tick + chunk writes would otherwise byte-interleave). {@link ReentrantLock} not
     * {@code synchronized}: the turn body runs on a virtual thread and JEP 444's monitor pins
     * the carrier OS thread for the full critical section incl. socket writes.
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    private volatile @Nullable ScheduledFuture<?> heartbeat;

    MentorSseChannel(SseEmitter emitter, ObjectMapper objectMapper, ScheduledExecutorService scheduler) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
    }

    /**
     * Wire emitter lifecycle callbacks. Must run before the orchestrator hands the emitter to
     * async work, otherwise a fast client-side abort can close the emitter before lifecycle
     * binding completes.
     */
    void bindLifecycle() {
        emitter.onCompletion(this::flagDisconnected);
        emitter.onTimeout(() -> {
            // INFO because emitter timeout (controller default 10 min) means the request
            // outlived the SSE window — operationally interesting; the runner may still be
            // alive on the server while the browser long-disconnected without a close.
            log.info("Mentor SSE emitter timed out; flagging disconnected");
            flagDisconnected();
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
            }
        });
        emitter.onError(throwable -> {
            log.debug("SseEmitter error on mentor turn: {}", throwable.toString());
            flagDisconnected();
        });
    }

    /**
     * Register the "ask Pi to stop generating" hook. Fires exactly once on the first transition
     * to disconnected. If the channel is already disconnected when the hook arrives, the hook
     * runs immediately on the calling thread — covers the race where the SSE lifecycle flips
     * the flag before the orchestrator attaches the runner.
     */
    @Override
    public void onDisconnect(Runnable hook) {
        if (clientGone.get()) {
            try {
                hook.run();
            } catch (RuntimeException ex) {
                log.debug("Disconnect hook threw (channel already disconnected): {}", ex.toString());
            }
            return;
        }
        disconnectHook.set(hook);
    }

    @Override
    public boolean isClientGone() {
        return clientGone.get();
    }

    /**
     * Schedule the comment-only keep-alive. Comments are SSE-spec lines starting with {@code :}
     * — they keep proxies (Traefik {@code idleTimeout=300s}) from killing the stream during
     * long Pi LLM calls but never reach {@code DefaultChatTransport}'s JSON parser.
     *
     * <p>The tick body acquires {@link #writeLock} so a ping never byte-interleaves with a
     * concurrent chunk write from the runner-event thread, and a post-{@link #closed} tick is
     * a clean no-op instead of poisoning the disconnect flag.
     */
    @Override
    public void startKeepAlive() {
        heartbeat = scheduler.scheduleAtFixedRate(
                () -> {
                    if (clientGone.get() || closed.get()) return;
                    long quietNs = System.nanoTime() - lastSendNanos.get();
                    if (quietNs < HEARTBEAT_QUIET_NS) return;
                    writeLock.lock();
                    try {
                        if (clientGone.get() || closed.get()) return;
                        try {
                            emitter.send(SseEmitter.event().comment("ping"));
                            lastSendNanos.set(System.nanoTime());
                        } catch (IOException | IllegalStateException ex) {
                            // Real disconnect: flip and stop. Spring's emitter callbacks fire and
                            // unwind the orchestrator naturally. DEBUG-log so a flaky proxy that
                            // closes the socket between chunks is observable (the lifecycle
                            // callbacks also fire, but this path can win the race).
                            log.debug("Heartbeat send failed; flagging disconnected: {}", ex.toString());
                            flagDisconnected();
                        }
                    } finally {
                        writeLock.unlock();
                    }
                },
                HEARTBEAT_INITIAL_DELAY_MS,
                HEARTBEAT_TICK_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Serialise a {@link UIMessageChunk} as one SSE {@code data:} frame. Throws
     * {@link ClientDisconnectedException} if the emitter is closed or the socket is dead so the
     * orchestrator can stop writing without poisoning the runner subscription.
     *
     * <p>A post-{@link #closed} call is a silent no-op: the orchestrator already terminated the
     * stream cleanly; a runner that emits a stray late event (e.g. a second {@code agent_end})
     * must not be treated as a client disconnect.
     */
    @Override
    public void send(UIMessageChunk chunk) {
        if (clientGone.get() || closed.get()) return;
        // Serialise OUTSIDE the lock to keep the critical section small.
        String payload;
        try {
            payload = objectMapper.writeValueAsString(chunk);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialise UIMessageChunk", e);
        }
        writeLock.lock();
        try {
            if (clientGone.get() || closed.get()) return;
            try {
                emitter.send(SseEmitter.event().data(payload));
                lastSendNanos.set(System.nanoTime());
                if (chunk instanceof UIMessageChunk.Finish) {
                    finishDelivered.set(true);
                }
            } catch (IOException e) {
                flagDisconnected();
                throw new ClientDisconnectedException("SSE send failed: " + e.getMessage(), e);
            } catch (IllegalStateException ex) {
                flagDisconnected();
                throw new ClientDisconnectedException("SSE emitter closed: " + ex.getMessage(), ex);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Natural-finish path: emit the {@code [DONE]} sentinel and complete the emitter. Cancels
     * the heartbeat first so a tick can't race {@code emitter.complete()}. Flips {@link #closed}
     * inside the same monitor as {@link #send} so subsequent writes short-circuit instead of
     * tripping Spring's post-complete {@link IllegalStateException}. Idempotent.
     */
    @Override
    public DeliveryOutcome completeWithDone() {
        cancelHeartbeat();
        writeLock.lock();
        try {
            if (closed.compareAndSet(false, true)) {
                try {
                    emitter.send(SseEmitter.event().data("[DONE]"));
                } catch (IOException | IllegalStateException ignored) {
                }
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                }
            }
            return finishDelivered.get() ? DeliveryOutcome.DELIVERED : DeliveryOutcome.NOT_DELIVERED;
        } finally {
            writeLock.unlock();
        }
    }

    /** Error path: best-effort emit an {@link UIMessageChunk.Error} chunk + {@code [DONE]}. */
    @Override
    public void completeWithError(String errorText) {
        cancelHeartbeat();
        try {
            send(new UIMessageChunk.Error(errorText));
        } catch (RuntimeException ignored) {
        }
        completeWithDone();
    }

    /** 409 conflict path: emit a status hint + an error chunk + {@code [DONE]}. */
    @Override
    public void completeWithConflict() {
        cancelHeartbeat();
        try {
            send(UIMessageChunk.DataMentorStatus.of("conflict", "another turn is in flight for this thread"));
            send(new UIMessageChunk.Error("Another mentor turn is already in flight for this thread."));
        } catch (RuntimeException ignored) {
        }
        completeWithDone();
    }

    /** Ends the response if no terminal did, so a turn can never leave the client waiting on an open stream. */
    @Override
    public void close() {
        completeWithDone();
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> h = this.heartbeat;
        if (h != null) {
            h.cancel(false);
            this.heartbeat = null;
        }
    }

    private void flagDisconnected() {
        if (clientGone.compareAndSet(false, true)) {
            Runnable hook = disconnectHook.getAndSet(null);
            if (hook != null) {
                try {
                    hook.run();
                } catch (RuntimeException ex) {
                    log.debug("Disconnect hook threw: {}", ex.toString());
                }
            }
        }
    }
}
