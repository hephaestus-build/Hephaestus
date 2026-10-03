package de.tum.cit.aet.hephaestus.agent.sandbox.remote;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.FrameRingBuffer;
import de.tum.cit.aet.hephaestus.agent.sandbox.FrameSubscription;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxRuntimeKey;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.MentorBusyException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSession;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.MentorSessionCommand;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.MentorSessionEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded replay and subscriber queues; a stream gap terminates the session rather than dropping data. */
final class RemoteAttachedSandbox implements AttachedSandbox {
    private final SandboxIdentity identity;
    private final WorkerSession worker;
    private final InteractiveSandboxRuntimeKey runtime;
    private final ObjectMapper mapper;
    private final InteractiveSandboxProperties properties;
    private final Consumer<RemoteAttachedSandbox> onClosed;
    private final FrameRingBuffer frames;
    private final Set<FrameSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final MeterRegistry meters;
    private final Map<UUID, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> opened = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int frameByteBudget;

    RemoteAttachedSandbox(
            SandboxIdentity identity,
            WorkerSession worker,
            InteractiveSandboxRuntimeKey runtime,
            ObjectMapper mapper,
            InteractiveSandboxProperties properties,
            Consumer<RemoteAttachedSandbox> onClosed,
            MeterRegistry meters) {
        this.identity = identity;
        this.worker = worker;
        this.runtime = runtime;
        this.mapper = mapper;
        this.properties = properties;
        this.frameByteBudget = properties.maxFrameBytes();
        this.onClosed = onClosed;
        this.meters = meters;
        frames = new FrameRingBuffer(
                properties.ringBufferFrames(), meters.counter(AgentMetrics.MENTOR_RELAY_REPLAY_DROPPED));
    }

    WorkerSession worker() {
        return worker;
    }

    InteractiveSandboxRuntimeKey runtime() {
        return runtime;
    }

    boolean isLive() {
        return !closed.get() && worker.isOpen() && !worker.isDraining();
    }

    boolean isReady() {
        return isLive() && opened.isDone() && !opened.isCompletedExceptionally();
    }

    @Override
    public SandboxIdentity identity() {
        return identity;
    }

    void open(JsonNode body) {
        try {
            JsonNode reply = request(
                    MentorSessionCommand.Operation.OPEN,
                    body,
                    Duration.ofSeconds(properties.attachFirstFrameTimeoutSeconds() + 60L));
            int budget = reply.path("frameByteBudget").asInt(0);
            if (budget <= 0 || budget > InteractiveSandboxProperties.MAX_FRAME_BYTES)
                throw new InteractiveSandboxException("Invalid worker frame byte budget");
            frameByteBudget = budget;
            opened.complete(null);
        } catch (RuntimeException failure) {
            opened.completeExceptionally(failure);
            throw failure;
        }
    }

    void awaitOpen() {
        await(opened, Duration.ofSeconds(properties.attachFirstFrameTimeoutSeconds() + 60L));
    }

    private JsonNode request(MentorSessionCommand.Operation operation, JsonNode body, Duration timeout) {
        if (!isLive()) throw new InteractiveSandboxException("Worker session is unavailable");
        UUID requestId = UUID.randomUUID();
        var result = new CompletableFuture<JsonNode>();
        pending.put(requestId, result);
        try {
            if (!worker.send(new MentorSessionCommand(identity.sessionId(), requestId, operation, body))) {
                lost("The worker connection ended. Please try again.");
                throw new InteractiveSandboxException("Worker session send failed");
            }
            return await(result, timeout);
        } catch (RuntimeException failure) {
            // A missing acknowledgement leaves command execution unknown. Do not reuse that runtime.
            if (operation != MentorSessionCommand.Operation.OPEN) close(Duration.ZERO);
            throw failure;
        } finally {
            pending.remove(requestId);
        }
    }

    private static <T> T await(CompletableFuture<T> result, Duration timeout) {
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InteractiveSandboxException("Interrupted while waiting for worker", interrupted);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                runtime.addSuppressed(failure);
                throw runtime;
            }
            throw new InteractiveSandboxException("Worker operation failed", failure);
        } catch (TimeoutException failure) {
            throw new InteractiveSandboxException("Worker operation timed out", failure);
        }
    }

    @Override
    public void send(JsonNode frame) {
        if (mapper.writeValueAsBytes(frame).length > frameByteBudget) {
            throw new InteractiveSandboxException("Runner frame exceeds gateway byte budget");
        }
        request(
                MentorSessionCommand.Operation.SEND,
                frame,
                Duration.ofMillis(properties.stdinWriteTimeoutMs() + 1000L));
    }

    @Override
    public void bindTurn(UUID turnId, @Nullable LlmPriceSnapshot price) {
        var body = mapper.createObjectNode().put("turnId", turnId.toString());
        body.set("price", mapper.valueToTree(price));
        request(MentorSessionCommand.Operation.BIND_TURN, body, Duration.ofSeconds(10));
    }

    @Override
    public void unbindTurn(UUID turnId) {
        // A retired worker session already revoked its credential. Cleanup must not replace a
        // prompt's original stream-loss error or invalidate a reply already committed to the ledger.
        if (!isLive()) return;
        try {
            request(
                    MentorSessionCommand.Operation.UNBIND_TURN,
                    mapper.createObjectNode().put("turnId", turnId.toString()),
                    Duration.ofSeconds(10));
        } catch (InteractiveSandboxException failure) {
            close(Duration.ZERO);
        }
    }

    synchronized void receive(MentorSessionEvent event) {
        if (closed.get()) return;
        switch (event.kind()) {
            case FRAME -> {
                if (mapper.writeValueAsBytes(event.body()).length > frameByteBudget) {
                    close(Duration.ZERO);
                    return;
                }
                frames.offer(event.body());
                subscriptions.forEach(sub -> sub.offer(event.body()));
            }
            case ACK -> {
                if (event.requestId() != null) {
                    var future = pending.get(event.requestId());
                    if (future != null) future.complete(event.body());
                }
            }
            case BUSY, FAILED -> {
                RuntimeException error = event.kind() == MentorSessionEvent.Kind.BUSY
                        ? new MentorBusyException()
                        : new InteractiveSandboxException("Worker session operation failed");
                if (event.requestId() != null) {
                    var future = pending.get(event.requestId());
                    if (future != null) future.completeExceptionally(error);
                }
            }
            case CLOSED -> end("The worker session ended. Please try again.", true);
        }
    }

    @Override
    public Disposable subscribe(Consumer<JsonNode> listener, Runnable onLost) {
        return subscribeAfter(-1, listener, onLost);
    }

    @Override
    public synchronized Disposable subscribeFromNow(Consumer<JsonNode> listener, Runnable onLost) {
        return subscribeAfter(frames.latestSequence(), listener, onLost);
    }

    private synchronized Disposable subscribeAfter(long after, Consumer<JsonNode> listener, Runnable onLost) {
        var holder = new FrameSubscription[1];
        // The hub carries all sessions: a full subscriber queue must not delay another session's ACK.
        var sub = new FrameSubscription(
                listener,
                properties.subscriberQueueCapacity(),
                Duration.ZERO,
                meters.counter(AgentMetrics.MENTOR_RELAY_SUBSCRIBER_CUTOFF),
                meters.counter(AgentMetrics.MENTOR_RELAY_SUBSCRIBER_ERROR),
                onLost,
                () -> subscriptions.remove(holder[0]));
        holder[0] = sub;
        if (closed.get()) {
            sub.lose("the worker session ended");
            return sub;
        }
        subscriptions.add(sub);
        sub.start();
        frames.snapshotSince(after).forEach(sub::offer);
        return sub;
    }

    synchronized void lost(String message) {
        end(message, false);
    }

    private synchronized void end(String message, boolean drainQueuedFrames) {
        if (!closed.compareAndSet(false, true)) return;
        var failure = new InteractiveSandboxException(message);
        opened.completeExceptionally(failure);
        pending.values().forEach(f -> f.completeExceptionally(failure));
        subscriptions.forEach(sub -> {
            if (drainQueuedFrames) sub.endOfStream();
            else sub.lose("the worker session ended");
        });
        onClosed.accept(this);
    }

    @Override
    public void close(Duration graceTimeout) {
        if (closed.get()) return;
        worker.send(new MentorSessionCommand(
                identity.sessionId(),
                UUID.randomUUID(),
                MentorSessionCommand.Operation.CLOSE,
                mapper.createObjectNode().put("graceMillis", graceTimeout.toMillis())));
        lost("The worker session ended. Please try again.");
    }
}
