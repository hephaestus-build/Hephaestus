package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorRunnerException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorRunnerTimeoutException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 wrapper around an {@link AttachedSandbox}. Owns id correlation, response
 * routing, event fan-out, and runner-callback dispatch. Created per attached session.
 */
public final class MentorRunnerClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MentorRunnerClient.class);

    public static final int PROTOCOL_VERSION = 1;
    public static final Duration DEFAULT_CONTROL_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Deadline for the {@code prompt} JSON-RPC <em>ack</em> — not a turn duration. The runner
     * replies {@code {accepted:true}} synchronously after binding the thread and arming its
     * watchdog (see {@code pi-mentor-runner.ts#handlePrompt} → {@code sendResult(id, {accepted:true})}),
     * then fires {@code runtime.session.prompt(...)} fire-and-forget. Turn streaming and
     * cancellation are observed via subscribed events; the runner's 120s + 30s watchdog owns
     * the actual turn deadline. This timeout exists only to surface a runner hang during
     * {@code bindThread}/{@code switchSession} (typical: tens of milliseconds; pathological:
     * multiple seconds on a large session JSONL). The generous 165s upper bound leaves slack
     * for a cold-start cohort awaiting {@code ensureRuntime} behind a single mutex; in
     * practice the future resolves in &lt;1s on warm runners.
     */
    public static final Duration DEFAULT_PROMPT_TIMEOUT = Duration.ofSeconds(165);

    private final AttachedSandbox sandbox;
    private final ObjectMapper objectMapper;
    private final Consumer<JsonNode> onEvent;
    private final Runnable onStreamLost;
    private final Function<FetchContextRequest, JsonNode> fetchContextHandler;
    private final ScheduledExecutorService timeoutScheduler;

    /**
     * Thread this client is bound to. The underlying sandbox is shared by
     * {@code (userId, workspaceId)} so multiple clients (one per concurrent thread of the
     * same user-workspace) subscribe to the SAME frame stream. Without this filter, every
     * client would see every thread's events — chat sessions would cross-talk into each
     * other's browser tabs and translator state.
     */
    private final UUID boundThreadId;

    private final AtomicLong idGen = new AtomicLong();
    private final ConcurrentHashMap<Long, PendingCall> pending = new ConcurrentHashMap<>();

    /**
     * Set once this client's stream stopped being intact — the sandbox cut the subscription off, or a
     * frame for this thread arrived malformed. No reply or event is processed after it.
     */
    private final AtomicBoolean streamLost = new AtomicBoolean();

    @Nullable
    private Disposable subscription;

    public MentorRunnerClient(
            AttachedSandbox sandbox,
            ObjectMapper objectMapper,
            Consumer<JsonNode> onEvent,
            Runnable onStreamLost,
            Function<FetchContextRequest, JsonNode> fetchContextHandler,
            ScheduledExecutorService timeoutScheduler,
            UUID boundThreadId) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.onEvent = Objects.requireNonNull(onEvent, "onEvent");
        this.onStreamLost = Objects.requireNonNull(onStreamLost, "onStreamLost");
        this.fetchContextHandler = Objects.requireNonNull(fetchContextHandler, "fetchContextHandler");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.boundThreadId = Objects.requireNonNull(boundThreadId, "boundThreadId");
    }

    /** Subscribe to the underlying sandbox; safe to call once per client instance. */
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        // subscribeFromNow: skip ring-buffer replay of frames from prior turns on the same
        // reused sandbox. Without this, a second turn replays turn-1's agent_end event and
        // completes instantly with stale data.
        this.subscription = sandbox.subscribeFromNow(this::onFrame, () -> loseStream("the subscription was cut off"));
    }

    public CompletableFuture<JsonNode> hello() {
        return call("hello", objectMapper.createObjectNode(), DEFAULT_CONTROL_TIMEOUT);
    }

    public CompletableFuture<JsonNode> openThread(UUID threadId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId.toString());
        return call("open_thread", params, DEFAULT_CONTROL_TIMEOUT);
    }

    public CompletableFuture<JsonNode> prompt(UUID threadId, String text) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId.toString());
        params.put("text", text);
        return call("prompt", params, DEFAULT_PROMPT_TIMEOUT);
    }

    public CompletableFuture<JsonNode> abort(UUID threadId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId.toString());
        return call("abort", params, DEFAULT_CONTROL_TIMEOUT);
    }

    public CompletableFuture<JsonNode> closeThread(UUID threadId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId.toString());
        return call("close_thread", params, DEFAULT_CONTROL_TIMEOUT);
    }

    public CompletableFuture<JsonNode> shutdown() {
        return call("shutdown", objectMapper.createObjectNode(), DEFAULT_CONTROL_TIMEOUT);
    }

    @Override
    public synchronized void close() {
        // synchronized so close() races with start() are deterministic — both touch
        // `subscription`. Pending-call drains via the lock-free ConcurrentHashMap so contention
        // here is bounded to the dispose() path.
        if (subscription != null) {
            try {
                subscription.dispose();
            } catch (Exception ignored) {
                // Idempotent dispose; nothing actionable.
            }
            subscription = null;
        }
        failPending("Runner client closed");
    }

    private void loseStream(String reason) {
        if (!streamLost.compareAndSet(false, true)) {
            return;
        }
        log.warn("Runner stream for thread {} is no longer intact: {}", boundThreadId, reason);
        // Report the loss before failing pending calls, so it is the turn's recorded cause.
        onStreamLost.run();
        failPending("Runner event stream lost");
    }

    private void failPending(String reason) {
        pending.forEach((id, p) -> {
            if (pending.remove(id, p)) {
                p.timeoutTask.cancel(false);
                p.future.completeExceptionally(new InteractiveSandboxException(reason));
            }
        });
    }

    private CompletableFuture<JsonNode> call(String method, ObjectNode params, Duration timeout) {
        long id = idGen.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(
                () -> {
                    PendingCall removed = pending.remove(id);
                    if (removed != null) {
                        removed.future.completeExceptionally(new MentorRunnerTimeoutException(
                                "Runner did not respond to " + method + " within " + timeout));
                    }
                },
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        pending.put(id, new PendingCall(future, timeoutTask, method));

        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("method", method);
        frame.set("params", params);
        try {
            sandbox.send(frame);
        } catch (InteractiveSandboxException e) {
            pending.remove(id);
            timeoutTask.cancel(false);
            future.completeExceptionally(e);
        }
        // The request still reaches the runner (abort, close_thread), but its reply never can.
        if (streamLost.get() && pending.remove(id) != null) {
            timeoutTask.cancel(false);
            future.completeExceptionally(new InteractiveSandboxException("Runner event stream lost"));
        }
        return future;
    }

    /**
     * Frames arrive from a sandbox, so their shape is checked here. A frame that breaks the protocol
     * and is not addressed to another thread may have carried part of this thread's turn, so it ends
     * the stream rather than being skipped; an unknown method is a benign notification.
     */
    private void onFrame(JsonNode frame) {
        if (streamLost.get()) {
            return;
        }
        if (!frame.isObject()) {
            loseStream("a runner frame was not a JSON object");
            return;
        }
        if (frame.has("method")) {
            JsonNode methodNode = frame.get("method");
            if (!methodNode.isString()) {
                loseStream("a runner frame's method was not a string");
                return;
            }
            String method = methodNode.asString();
            if ("event".equals(method)) {
                handleEvent(frame);
            } else if ("fetch_context".equals(method)) {
                handleFetchContext(frame);
            } else {
                log.debug("Unknown runner-side method '{}' — ignoring", method);
            }
            return;
        }
        if (frame.path("id").isIntegralNumber()) {
            handleResponse(frame);
        } else {
            loseStream("a runner frame had neither a method nor a numeric reply id");
        }
    }

    private void handleResponse(JsonNode frame) {
        long id = frame.get("id").asLong();
        PendingCall call = pending.remove(id);
        if (call == null) {
            return;
        }
        call.timeoutTask.cancel(false);
        if (frame.has("error") && !frame.get("error").isNull()) {
            JsonNode error = frame.get("error");
            int code = error.has("code") ? error.get("code").asInt() : -32000;
            String message = error.has("message") ? error.get("message").asString() : "runner error";
            call.future.completeExceptionally(new MentorRunnerException(code, message, error));
        } else {
            JsonNode result = frame.has("result") ? frame.get("result") : objectMapper.nullNode();
            call.future.complete(result);
        }
    }

    private void handleEvent(JsonNode frame) {
        JsonNode params = frame.path("params");
        // Per-thread fan-out: the sandbox is shared by (userId, workspaceId), so a second
        // chat tab in the same workspace subscribes to the same frame stream. Drop any frame
        // whose threadId doesn't match the one this client is bound to — without the filter,
        // tab-A's translator sees tab-B's text deltas and ships them down tab-A's wire.
        // Notification-type frames (`runner_ready`) ship with `threadId: null` and pass
        // through here for ALL clients; the translator drops them by event-type.
        JsonNode threadId = params.path("threadId");
        if (threadId.isString() && !boundThreadId.toString().equals(threadId.asString())) {
            return;
        }
        JsonNode event = params.path("event");
        if (!(threadId.isString() || threadId.isNull()) || !event.path("type").isString()) {
            loseStream("an event frame did not match the protocol");
            return;
        }
        try {
            onEvent.accept(event);
        } catch (RuntimeException e) {
            log.warn("Runner event handler threw: {}", e.getMessage(), e);
        }
    }

    private void handleFetchContext(JsonNode frame) {
        // Runner-originated callbacks carry a string id (`fc-<uuid>`); Java-originated calls use
        // numeric ids from our own AtomicLong. We MUST echo the runner's id back unchanged — the
        // runner indexes `pendingFetchContexts` by string key, so any coercion (asLong → 0) silently
        // breaks correlation and stalls the LLM tool call until the 10s timeout fires.
        JsonNode idNode = frame.get("id");
        JsonNode params = frame.get("params");
        if (params == null) {
            sendCallbackError(idNode, -32600, "missing params");
            return;
        }
        String threadIdStr = params.has("threadId") ? params.get("threadId").asString() : null;
        String path = params.has("path") ? params.get("path").asString() : null;
        if (threadIdStr == null || path == null) {
            sendCallbackError(idNode, -32600, "missing threadId or path");
            return;
        }
        UUID threadId;
        try {
            threadId = UUID.fromString(threadIdStr);
        } catch (IllegalArgumentException ex) {
            sendCallbackError(idNode, -32600, "invalid threadId");
            return;
        }
        if (!boundThreadId.equals(threadId)) {
            return;
        }
        try {
            JsonNode result = fetchContextHandler.apply(new FetchContextRequest(threadId, path));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", idNode != null ? idNode : objectMapper.nullNode());
            ObjectNode resultEnvelope = response.putObject("result");
            resultEnvelope.set("content", result != null ? result : objectMapper.nullNode());
            sandbox.send(response);
        } catch (RuntimeException e) {
            log.warn("fetch_context callback failed: {}", e.getMessage(), e);
            sendCallbackError(idNode, -32000, e.getMessage() != null ? e.getMessage() : "fetch_context failed");
        }
    }

    private void sendCallbackError(@Nullable JsonNode id, int code, String message) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id != null ? id : objectMapper.nullNode());
            ObjectNode error = response.putObject("error");
            error.put("code", code);
            error.put("message", message);
            sandbox.send(response);
        } catch (InteractiveSandboxException e) {
            log.warn("Failed to send fetch_context error response: {}", e.getMessage(), e);
        }
    }

    /** Server-callback request from the runner. */
    public record FetchContextRequest(UUID threadId, String path) {}

    private record PendingCall(CompletableFuture<JsonNode> future, ScheduledFuture<?> timeoutTask, String method) {}
}
