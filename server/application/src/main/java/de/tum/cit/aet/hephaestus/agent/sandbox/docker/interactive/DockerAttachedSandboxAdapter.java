package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandboxState;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.EvictionReason;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-session adapter: owns the docker-exec subprocess, JSONL pump + writer, ring buffer, and
 * subscriber fan-out. State transitions {@code ATTACHED → CLOSING → CLOSED} are CAS-guarded.
 */
public final class DockerAttachedSandboxAdapter implements AttachedSandbox, StdinWriteWatchdog.StallTarget {

    private static final Logger log = LoggerFactory.getLogger(DockerAttachedSandboxAdapter.class);

    private static final String MDC_SESSION_ID = "mentor.sessionId";
    private static final String MDC_CONTAINER_ID = "mentor.containerId";

    private final UUID sessionId;
    private final SandboxIdentity identity;
    private final String containerId;
    private final String networkId;
    private final InteractiveSandboxRuntimeKey runtimeKey;

    private final PiProcessHandle process;
    private final JsonlStdoutPump pump;
    private final JsonlStdinWriter writer;
    private final FrameRingBuffer ring;
    private final CopyOnWriteArrayList<FrameSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final InteractiveSandboxMetrics metrics;
    private final Duration defaultGrace;

    private final AtomicReference<AttachedSandboxState> state = new AtomicReference<>(AttachedSandboxState.ATTACHED);
    private final AtomicReference<@Nullable EvictionReason> terminalReason = new AtomicReference<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final CompletableFuture<Void> firstFrame = new CompletableFuture<>();

    private volatile Instant lastActivityAt;
    private final Instant attachedAt;

    /** Serialises pump fan-out with subscribe(): guarantees snapshot replay precedes live frames. */
    private final Object subscriberLock = new Object();

    private final Consumer<DockerAttachedSandboxAdapter> onClosed;
    private final LifecycleOps lifecycle;
    private final int subscriberQueueCapacity;
    /** Platform-thread executor: docker-java sync calls pin virtual carriers on JDK 21. */
    private final Executor closeExecutor;

    DockerAttachedSandboxAdapter(
            UUID sessionId,
            String userId,
            String workspaceId,
            String containerId,
            String networkId,
            InteractiveSandboxRuntimeKey runtimeKey,
            PiProcessHandle process,
            ObjectMapper mapper,
            FrameRingBuffer ring,
            int subscriberQueueCapacity,
            int stdinWriteTimeoutMs,
            int sendQueueCapacity,
            int maxLineChars,
            Duration defaultGrace,
            InteractiveSandboxMetrics metrics,
            LifecycleOps lifecycle,
            Executor closeExecutor,
            Consumer<DockerAttachedSandboxAdapter> onClosed) {
        this.sessionId = sessionId;
        this.identity = new SandboxIdentity(sessionId, userId, workspaceId);
        this.containerId = containerId;
        this.networkId = networkId;
        this.runtimeKey = runtimeKey;
        this.process = process;
        this.ring = ring;
        this.metrics = metrics;
        this.lifecycle = lifecycle;
        this.onClosed = onClosed;
        this.subscriberQueueCapacity = subscriberQueueCapacity;
        this.defaultGrace = defaultGrace;
        this.closeExecutor = closeExecutor;
        this.attachedAt = Instant.now();
        this.lastActivityAt = this.attachedAt;

        Map<String, String> mdcSnapshot = mdcContext();
        this.writer = new JsonlStdinWriter(
                sessionId,
                process.stdin(),
                mapper,
                sendQueueCapacity,
                stdinWriteTimeoutMs,
                metrics.sendRejectedQueueFull,
                metrics.sendRejectedWriteTimeout,
                metrics.sendRejectedBrokenPipe,
                metrics.sendRejectedClosed,
                metrics.sendBytes,
                () -> terminate(EvictionReason.ERROR),
                mdcSnapshot);
        this.pump = new JsonlStdoutPump(
                sessionId,
                process.stdout(),
                mapper,
                this::onFrame,
                this::onEof,
                process::exitValueOrAlive,
                metrics.frameParseError,
                maxLineChars,
                mdcSnapshot);
    }

    void start() {
        pump.start();
        writer.start();
    }

    @Override
    public SandboxIdentity identity() {
        return identity;
    }

    public AttachedSandboxState state() {
        return Objects.requireNonNull(state.get());
    }

    @Override
    public Instant lastActivityAt() {
        return lastActivityAt;
    }

    @Override
    public Duration idleFor() {
        return Duration.between(lastActivityAt, Instant.now());
    }

    @Override
    public void send(JsonNode frame) {
        if (state.get() != AttachedSandboxState.ATTACHED) {
            metrics.sendRejectedClosed.increment();
            throw new InteractiveSandboxException("Session is " + state.get());
        }
        writer.send(frame);
        lastActivityAt = Instant.now();
    }

    @Override
    public Disposable subscribe(Consumer<JsonNode> listener) {
        return subscribeInternal(listener, -1L);
    }

    @Override
    public Disposable subscribeFromNow(Consumer<JsonNode> listener) {
        return subscribeInternal(listener, ring.latestSequence());
    }

    private Disposable subscribeInternal(Consumer<JsonNode> listener, long replaySince) {
        // CLOSING: late add would leak its dispatcher — runClose has already drained subscriptions.
        AttachedSandboxState s = state.get();
        if (s != AttachedSandboxState.ATTACHED) {
            FrameSubscription disposed =
                    new FrameSubscription(listener, 1, metrics.subscriberDropped, metrics.subscriberError, () -> {});
            disposed.dispose();
            return disposed;
        }
        FrameSubscription[] holder = new FrameSubscription[1];
        FrameSubscription sub = new FrameSubscription(
                listener,
                subscriberQueueCapacity,
                metrics.subscriberDropped,
                metrics.subscriberError,
                () -> subscriptions.remove(holder[0]));
        holder[0] = sub;

        // Snapshot+add atomic against pump fan-out → snapshot replay strictly precedes live frames.
        synchronized (subscriberLock) {
            if (state.get() != AttachedSandboxState.ATTACHED) {
                sub.dispose();
                return sub;
            }
            List<JsonNode> snapshot = ring.snapshotSince(replaySince);
            subscriptions.add(sub);
            sub.start();
            for (JsonNode frame : snapshot) {
                sub.offer(frame);
            }
        }
        return sub;
    }

    @Override
    public void close(Duration graceTimeout) {
        Duration grace = graceTimeout != null ? graceTimeout : defaultGrace;
        if (state.compareAndSet(AttachedSandboxState.ATTACHED, AttachedSandboxState.CLOSING)) {
            terminalReason.compareAndSet(null, EvictionReason.MANUAL);
            runCloseAsync(grace);
        }
        waitForClose(grace.plusSeconds(5));
    }

    @Override
    public boolean writeStalled(long nowNanos) {
        return writer.writeStalled(nowNanos);
    }

    @Override
    public void onWriteTimeout() {
        terminate(EvictionReason.ERROR);
        // Closes stdin FD; interrupt() does not unblock OutputStream.write on Linux.
        process.destroyForcibly();
    }

    /** Forced close with a specific reason and the configured default grace. Idempotent. */
    void terminate(EvictionReason reason) {
        if (!state.compareAndSet(AttachedSandboxState.ATTACHED, AttachedSandboxState.CLOSING)) {
            return;
        }
        terminalReason.compareAndSet(null, reason);
        if (!firstFrame.isDone()) {
            firstFrame.completeExceptionally(new InteractiveSandboxException("Terminated before first frame"));
        }
        runCloseAsync(defaultGrace);
    }

    void awaitClosed(Duration timeout) {
        waitForClose(timeout);
    }

    /**
     * @return {@code true} on first frame, {@code false} on pure timeout
     * @throws InteractiveSandboxException if the pump/writer terminated before any frame
     */
    boolean awaitFirstFrame(Duration timeout) {
        try {
            firstFrame.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException te) {
            return false;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof InteractiveSandboxException ise) {
                ise.addSuppressed(ee);
                throw ise;
            }
            throw new InteractiveSandboxException("First frame failed", ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InteractiveSandboxException("Interrupted while waiting for first frame", ie);
        }
    }

    String containerId() {
        return containerId;
    }

    String networkId() {
        return networkId;
    }

    boolean hasRuntimeKey(InteractiveSandboxRuntimeKey requested) {
        return runtimeKey.equals(requested);
    }

    @Nullable
    EvictionReason terminalReason() {
        return terminalReason.get();
    }

    Instant attachedAt() {
        return attachedAt;
    }

    private void onFrame(JsonNode frame, int wireBytes) {
        lastActivityAt = Instant.now();
        if (!firstFrame.isDone()) {
            firstFrame.complete(null);
        }
        metrics.recvBytes.increment(wireBytes);
        synchronized (subscriberLock) {
            ring.offer(frame);
            for (FrameSubscription sub : subscriptions) {
                sub.offer(frame);
            }
        }
    }

    // The exec FD can close briefly before Process.exitValue() becomes available.
    private static final Duration EOF_EXIT_WAIT = Duration.ofSeconds(2);

    private void onEof(int initialExitCode) {
        int exit = initialExitCode;
        if (exit < 0) {
            process.waitFor(EOF_EXIT_WAIT);
            exit = process.exitValueOrAlive();
        }
        terminate(exit == 0 ? EvictionReason.NATURAL_EXIT : EvictionReason.ERROR);
    }

    private void runCloseAsync(Duration graceTimeout) {
        try {
            closeExecutor.execute(() -> runClose(graceTimeout));
        } catch (RejectedExecutionException ree) {
            // Executor shut down (e.g. by a maintenance tick during Spring destruction).
            log.warn("closeExecutor rejected runClose for sessionId={} — running inline", sessionId);
            runClose(graceTimeout);
        }
    }

    private void runClose(Duration graceTimeout) {
        applyMdc();
        try {
            int graceSeconds = (int) Math.max(0, graceTimeout.toSeconds());
            log.info("Closing attached sandbox: reason={}, graceSeconds={}", terminalReason.get(), graceSeconds);

            try {
                writer.close();
            } catch (Exception e) {
                log.debug("writer.close() during sandbox close threw: {}", e.getMessage());
            }

            try {
                lifecycle.stopContainer(containerId, graceSeconds);
            } catch (Exception e) {
                log.warn("stopContainer failed during close: {}", e.getMessage());
            }

            // destroyForcibly if docker stop didn't propagate to the exec subprocess — prevents
            // the close virtual thread from waiting forever on a hung docker daemon.
            Duration waitBudget = graceTimeout.plusSeconds(5);
            if (!process.waitFor(waitBudget)) {
                log.warn("Exec subprocess still alive after stop + grace; destroyForcibly");
                process.destroyForcibly();
            }

            int subscriberCount = subscriptions.size();
            for (FrameSubscription sub : subscriptions) {
                try {
                    sub.dispose();
                } catch (Exception ignored) {
                }
            }
            subscriptions.clear();

            try {
                lifecycle.removeContainer(containerId);
            } catch (Exception e) {
                log.warn("forceRemove container failed during close: {}", e.getMessage());
            }
            if (networkId != null) {
                try {
                    lifecycle.disconnectAndRemoveNetwork(networkId);
                } catch (Exception e) {
                    log.warn("Network teardown failed during close: {}", e.getMessage());
                }
            }

            try {
                process.awaitExitAndClose(Duration.ofSeconds(2));
            } catch (Exception ignored) {
            }

            metrics.lifetime.record(Duration.between(attachedAt, Instant.now()));
            metrics.subscribersAtClose.record(subscriberCount);
            EvictionReason reason = terminalReason.get();
            if (reason == null) reason = EvictionReason.ERROR;
            metrics.evictionsBy(reason).increment();

            state.set(AttachedSandboxState.CLOSED);
            try {
                onClosed.accept(this);
            } catch (Exception e) {
                log.warn("Registry onClosed callback threw: {}", e.getMessage());
            }
            closed.complete(null);
            log.info(
                    "Sandbox closed: reason={}, lifetimeMs={}",
                    reason,
                    Duration.between(attachedAt, Instant.now()).toMillis());
        } catch (Throwable t) {
            log.error("Unexpected error during sandbox close", t);
            state.set(AttachedSandboxState.CLOSED);
            // Must still run onClosed: otherwise registry + watchdog hold this sandbox forever
            // and any future attach for (userId, workspaceId) gets a permanent DUPLICATE.
            try {
                onClosed.accept(this);
            } catch (Throwable cb) {
                log.warn("Registry onClosed callback threw during error path: {}", cb.getMessage());
            }
            closed.completeExceptionally(t);
        } finally {
            MDC.clear();
        }
    }

    private void waitForClose(Duration timeout) {
        try {
            closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Best-effort: caller may have set a tight grace and we exceeded it.
        }
    }

    private Map<String, String> mdcContext() {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        Map<String, String> own = new HashMap<>(ctx != null ? ctx : Map.of());
        own.put(MDC_SESSION_ID, sessionId.toString());
        own.put(MDC_CONTAINER_ID, containerId);
        return Map.copyOf(own);
    }

    private void applyMdc() {
        MDC.put(MDC_SESSION_ID, sessionId.toString());
        MDC.put(MDC_CONTAINER_ID, containerId);
    }

    /** Adapter-provided container / network teardown. */
    interface LifecycleOps {
        void stopContainer(String containerId, int graceSeconds);

        void removeContainer(String containerId);

        void disconnectAndRemoveNetwork(String networkId);
    }
}
