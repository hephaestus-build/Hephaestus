package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;

/**
 * Live handle to one attached sandbox session: bidirectional JSONL channel plus fan-out and idle
 * bookkeeping. {@link #subscribe} delivers a snapshot of the ring buffer followed by live frames,
 * each subscriber on its own bounded queue + virtual-thread dispatcher. Delivery never skips a
 * frame: a full queue holds up the stream for a bounded time, and a subscriber that would miss a
 * frame — it stalled, its listener threw, or the runner wrote a line that could not be read — is cut
 * off instead. After termination, {@code send} throws and {@code subscribe} returns a disposed handle.
 */
public interface AttachedSandbox extends AutoCloseable {
    /**
     * Immutable identity of this session: {@code sessionId}, {@code userId}, {@code workspaceId}.
     * These never change over the session lifetime. Reach the individual keys via
     * {@code identity().sessionId()} etc.
     */
    SandboxIdentity identity();

    /**
     * Send a JSON frame to the runner's stdin. Blocks until the write completes or the configured
     * stdin timeout elapses. The bounded writer queue rejects with {@link InteractiveSandboxException}
     * when full — the primary backpressure signal to upstream callers.
     *
     * @throws InteractiveSandboxException if the session is closed, the pipe is broken, the queue
     *     is full, the write times out, or the frame cannot be serialised
     */
    void send(JsonNode frame) throws InteractiveSandboxException;

    /**
     * @param listener invoked on a dedicated virtual thread per subscriber; may block
     * @return a {@link Disposable} whose {@code dispose()} is idempotent
     */
    Disposable subscribe(Consumer<JsonNode> listener);

    /**
     * Like {@link #subscribe}, but skips the ring-buffer replay and only delivers frames that
     * arrive after the subscription is registered. Use this for subsequent turns on a reused
     * sandbox to avoid replaying terminal events from prior turns.
     *
     * @param onLost runs once if this subscriber is cut off; no frame reaches the listener after it.
     *     Disposing the returned handle never triggers it. Must not block.
     */
    Disposable subscribeFromNow(Consumer<JsonNode> listener, Runnable onLost);

    /** Wall-clock of the last frame in either direction. */
    Instant lastActivityAt();

    Duration idleFor();

    /**
     * Stops the underlying container with {@code docker stop --time=graceTimeout} (SIGTERM →
     * grace → SIGKILL), then removes it. Idempotent. The hard SIGKILL mitigates the Pi SDK
     * abort-hang risk.
     */
    void close(Duration graceTimeout);

    /**
     * {@link AutoCloseable} contract: uses a hardcoded 30-second grace timeout. <b>This is NOT</b>
     * the registry's configured {@code hephaestus.mentor.grace-timeout-seconds} — callers who
     * want the configured default must call {@link #close(Duration)} explicitly.
     */
    @Override
    default void close() {
        close(Duration.ofSeconds(30));
    }
}
