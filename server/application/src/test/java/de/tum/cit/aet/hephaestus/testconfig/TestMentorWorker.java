package de.tum.cit.aet.hephaestus.testconfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerCapacityState;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerControlClient;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerMentorSessions;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxService;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real authenticated hub transport and worker session owner; only the external runner is deterministic. */
public final class TestMentorWorker implements AutoCloseable {
    public static final String SESSION_JSONL = "{\"type\":\"session\",\"version\":3}\n";
    private final ObjectMapper mapper;
    private final FrameCodec codec;
    private final WebSocket socket;
    private final WorkerMentorSessions sessions;
    private final ExecutorService dispatch = Executors.newSingleThreadExecutor();
    public final List<ProtocolSandbox> sandboxes = new CopyOnWriteArrayList<>();
    public final MentorProxyCredentialRegistry credentials = new MentorProxyCredentialRegistry();
    public final WorkerCapacityState capacity;

    public TestMentorWorker(URI hub, String workerId, String jwt, ObjectMapper mapper) throws Exception {
        this.mapper = mapper;
        codec = new FrameCodec(mapper);
        var environment = new MockEnvironment().withProperty("hephaestus.worker.capacity.mentor-max", "2");
        var properties = Binder.get(environment).bindOrCreate("hephaestus.worker", WorkerProperties.class);
        var interactive = Binder.get(environment).bindOrCreate("hephaestus.mentor", InteractiveSandboxProperties.class);
        capacity = new WorkerCapacityState(properties);
        var control = mock(WorkerControlClient.class);
        var sandboxService = mock(InteractiveSandboxService.class);
        when(sandboxService.attach(any())).thenAnswer(invocation -> {
            var attached = new ProtocolSandbox(invocation.getArgument(0));
            sandboxes.add(attached);
            return attached;
        });
        sessions = new WorkerMentorSessions(control, capacity, sandboxService, credentials, mapper, interactive);
        var ready = new CountDownLatch(1);
        socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + jwt)
                .buildAsync(hub, new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            var frame = codec.decode(partial.toString()).payload();
                            partial.setLength(0);
                            if (frame instanceof WorkerWelcome) ready.countDown();
                            if (frame instanceof MentorSessionCommand command)
                                dispatch.execute(() -> sessions.handle(command));
                        }
                        ws.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .get(10, TimeUnit.SECONDS);
        when(control.sendRequired(any())).thenAnswer(invocation -> {
            send(invocation.getArgument(0));
            return true;
        });
        send(new WorkerHello(workerId, List.of(FrameEnvelope.CURRENT_VERSION), "test"));
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker handshake timed out");
        send(capacity.snapshot());
    }

    public synchronized void send(WorkerControlFrame frame) {
        try {
            socket.sendText(codec.encode(FrameEnvelope.of(frame)), true).get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Worker fixture transport failed", failure);
        }
    }

    public void drain() {
        send(new Heartbeat(true));
        sessions.stop();
        send(capacity.snapshot());
    }

    @Override
    public void close() {
        sessions.stop();
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            socket.abort();
        }
        dispatch.shutdownNow();
    }

    public final class ProtocolSandbox implements AttachedSandbox {
        private final InteractiveSandboxSpec spec;
        private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
        private volatile Runnable onLost = () -> {};
        private volatile String thread = "";
        public volatile String restoredSession = "";
        public volatile boolean holdPrompt;
        public volatile boolean contextReceived;
        public volatile boolean promptReceived;
        private volatile long promptId;

        ProtocolSandbox(InteractiveSandboxSpec spec) {
            this.spec = spec;
        }

        @Override
        public SandboxIdentity identity() {
            return new SandboxIdentity(spec.sessionId(), spec.userId(), spec.workspaceId());
        }

        @Override
        public void send(JsonNode frame) {
            long id = frame.path("id").asLong();
            switch (frame.path("method").asString("")) {
                case "hello" -> result(id, mapper.createObjectNode().put("protocolVersion", 1));
                case "open_thread" -> {
                    thread = frame.path("params").path("threadId").asString();
                    restoredSession = frame.path("params").path("session").asString("");
                    result(id, mapper.createObjectNode());
                }
                case "prompt" -> {
                    if (credentials
                                    .validate(java.util.Objects.requireNonNull(
                                            java.util.Objects.requireNonNull(spec.networkPolicy())
                                                    .llmProxyToken()))
                                    .orElseThrow()
                                    .attempt()
                            == null) throw new IllegalStateException("Prompt has no worker billing binding");
                    promptId = id;
                    promptReceived = true;
                    if (holdPrompt) return;
                    var callback = mapper.createObjectNode()
                            .put("jsonrpc", "2.0")
                            .put("id", -100L)
                            .put("method", "fetch_context");
                    callback.putObject("params")
                            .put("threadId", thread)
                            .put("path", "context/observations_history.json");
                    push(callback);
                }
                case "close_thread", "abort" -> result(id, mapper.createObjectNode());
                default -> {
                    if (id == -100L && frame.has("result")) {
                        contextReceived = true;
                        finish();
                    }
                }
            }
        }

        private void result(long id, JsonNode result) {
            var frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id);
            frame.set("result", result);
            push(frame);
        }

        private void event(String type, Consumer<ObjectNode> detail) {
            var frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "event");
            var params = frame.putObject("params").put("threadId", thread);
            detail.accept(params.putObject("event").put("type", type));
            push(frame);
        }

        public void finish() {
            event("message_start", e -> e.putObject("message").put("role", "assistant"));
            for (String delta : List.of("Hello ", "from the worker.")) {
                event(
                        "message_update",
                        e -> e.putObject("assistantMessageEvent")
                                .put("type", "text_delta")
                                .put("contentIndex", 0)
                                .put("delta", delta));
            }
            event("message_end", e -> {
                var message = e.putObject("message").put("role", "assistant").put("stopReason", "stop");
                message.putArray("content").addObject().put("type", "text").put("text", "Hello from the worker.");
            });
            event("session_persisted", e -> e.put("jsonl", SESSION_JSONL));
            event("turn_end", e -> {});
            event("agent_end", e -> e.putArray("messages"));
            result(promptId, mapper.createObjectNode());
        }

        private void push(JsonNode frame) {
            listeners.forEach(listener -> listener.accept(frame));
        }

        @Override
        public Disposable subscribe(Consumer<JsonNode> listener, Runnable lost) {
            onLost = lost;
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public Disposable subscribeFromNow(Consumer<JsonNode> listener, Runnable lost) {
            return subscribe(listener, lost);
        }

        @Override
        public void close(Duration grace) {
            onLost.run();
            listeners.clear();
        }
    }
}
