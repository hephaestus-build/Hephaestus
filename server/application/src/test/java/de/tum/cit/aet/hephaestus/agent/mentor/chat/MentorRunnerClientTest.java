package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.MentorRunnerException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wire-level contract for {@link MentorRunnerClient}. The fake sandbox below is a hand-rolled
 * implementation of {@link AttachedSandbox} that buffers sent frames and pushes replies through
 * a registered listener — letting us drive the JSON-RPC protocol synchronously without Docker.
 */
class MentorRunnerClientTest extends BaseUnitTest {

    private FakeSandbox sandbox;
    private MentorRunnerClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<JsonNode> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger streamLost = new AtomicInteger();
    private final AtomicReference<MentorRunnerClient.FetchContextRequest> lastFetchContext = new AtomicReference<>();
    private ScheduledExecutorService scheduler;
    private UUID threadId;

    @BeforeEach
    void setUp() {
        sandbox = new FakeSandbox();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        threadId = UUID.randomUUID();
        client = new MentorRunnerClient(
                sandbox,
                mapper,
                events::add,
                streamLost::incrementAndGet,
                req -> {
                    lastFetchContext.set(req);
                    return mapper.createObjectNode().put("ok", true);
                },
                scheduler,
                threadId);
        client.start();
    }

    @AfterEach
    void tearDown() {
        client.close();
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("hello() correlates request id and resolves with the result body")
    void helloRoundtrip() throws Exception {
        CompletableFuture<JsonNode> future = client.hello();
        // Drain the sent frame and respond with a matching id.
        JsonNode request = sandbox.takeFrame();
        long id = request.get("id").asLong();
        assertThat(request.get("method").asString()).isEqualTo("hello");

        sandbox.pushFrame(responseOf(id, mapper.createObjectNode().put("protocolVersion", 1)));

        JsonNode result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.get("protocolVersion").asInt()).isEqualTo(1);
    }

    @Test
    void shouldSettleOnlyItsOwnCallWhenClientsShareASandbox() throws Exception {
        MentorRunnerClient other = new MentorRunnerClient(
                sandbox, mapper, e -> {}, () -> {}, req -> mapper.nullNode(), scheduler, UUID.randomUUID());
        other.start();
        CompletableFuture<JsonNode> mine = client.hello();
        long myId = sandbox.takeFrame().get("id").asLong();
        CompletableFuture<JsonNode> theirs = other.hello();
        long theirId = sandbox.takeFrame().get("id").asLong();

        // Every client on the sandbox receives every reply.
        sandbox.pushFrame(responseOf(myId, mapper.createObjectNode().put("for", "mine")));

        assertThat(mine.get(1, TimeUnit.SECONDS).get("for").asString()).isEqualTo("mine");
        assertThat(theirs).isNotDone();
        sandbox.pushFrame(responseOf(theirId, mapper.createObjectNode().put("for", "theirs")));
        assertThat(theirs.get(1, TimeUnit.SECONDS).get("for").asString()).isEqualTo("theirs");
        other.close();
    }

    @Test
    void shouldFailPendingAndLaterCallsWhenTheEventStreamIsLost() throws Exception {
        CompletableFuture<JsonNode> inFlight = client.openThread(threadId);
        sandbox.takeFrame();

        sandbox.onLost.run();

        assertThat(streamLost).hasValue(1);
        assertThatThrownBy(() -> inFlight.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InteractiveSandboxException.class);
        // A later request still reaches the runner, but fails at once: its reply cannot arrive.
        CompletableFuture<JsonNode> abort = client.abort(threadId);
        assertThat(sandbox.takeFrame().get("method").asString()).isEqualTo("abort");
        assertThat(abort).isCompletedExceptionally();
    }

    @Test
    void errorResponseBecomesException() throws Exception {
        CompletableFuture<JsonNode> future = client.openThread(UUID.randomUUID());
        JsonNode request = sandbox.takeFrame();
        long id = request.get("id").asLong();
        ObjectNode error = mapper.createObjectNode();
        ObjectNode errBody = error.putObject("error");
        errBody.put("code", -32001);
        errBody.put("message", "turn already in flight");
        error.put("jsonrpc", "2.0");
        error.put("id", id);
        sandbox.pushFrame(error);

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(MentorRunnerException.class)
                .hasMessageContaining("-32001");
    }

    @Test
    void poisoningErrorCodes() {
        MentorRunnerException pi =
                new MentorRunnerException(MentorRunnerException.CODE_PI_ERROR, "pi", mapper.nullNode());
        MentorRunnerException invalid =
                new MentorRunnerException(MentorRunnerException.CODE_INVALID_STATE, "bad", mapper.nullNode());
        MentorRunnerException other = new MentorRunnerException(-32001, "in flight", mapper.nullNode());

        assertThat(pi.poisonsSandbox()).isTrue();
        assertThat(invalid.poisonsSandbox()).isTrue();
        assertThat(other.poisonsSandbox()).isFalse();
    }

    @Test
    void eventFanOutToConsumer() {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "event");
        ObjectNode params = notification.putObject("params");
        params.put("threadId", threadId.toString());
        params.set(
                "event", mapper.createObjectNode().put("type", "runner_ready").put("protocolVersion", 1));

        sandbox.pushFrame(notification);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("type").asString()).isEqualTo("runner_ready");
    }

    @Test
    void shouldEndTheStreamWhenAnEventForThisThreadBreaksTheProtocol() {
        ObjectNode broken = eventFrame(threadId.toString());
        ((ObjectNode) broken.path("params").path("event")).remove("type");

        sandbox.pushFrame(broken);
        sandbox.pushFrame(eventFrame(threadId.toString()));

        assertThat(streamLost).hasValue(1);
        assertThat(events).as("nothing after the broken frame is processed").isEmpty();
    }

    @Test
    void shouldIgnoreAnotherThreadsFramesAndUnknownNotifications() {
        ObjectNode foreign = eventFrame(UUID.randomUUID().toString());
        ((ObjectNode) foreign.path("params").path("event")).remove("type");
        ObjectNode unknownMethod =
                mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "log");

        sandbox.pushFrame(foreign);
        sandbox.pushFrame(unknownMethod);
        sandbox.pushFrame(eventFrame(threadId.toString()));

        assertThat(streamLost).hasValue(0);
        assertThat(events).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"message_update", "agent_end"})
    void shouldDeliverOnlyRunnerReadyWithoutAThreadToEveryClient(String unaddressedType) {
        CopyOnWriteArrayList<JsonNode> otherEvents = new CopyOnWriteArrayList<>();
        AtomicInteger otherLost = new AtomicInteger();
        MentorRunnerClient other = new MentorRunnerClient(
                sandbox,
                mapper,
                otherEvents::add,
                otherLost::incrementAndGet,
                req -> mapper.nullNode(),
                scheduler,
                UUID.randomUUID());
        other.start();

        sandbox.pushFrame(unaddressed("runner_ready"));
        sandbox.pushFrame(unaddressed(unaddressedType));

        assertThat(events).extracting(e -> e.get("type").asString()).containsExactly("runner_ready");
        assertThat(otherEvents).extracting(e -> e.get("type").asString()).containsExactly("runner_ready");
        assertThat(streamLost).hasValue(1);
        assertThat(otherLost).hasValue(1);
        other.close();
    }

    private ObjectNode unaddressed(String type) {
        ObjectNode frame = eventFrame(threadId.toString());
        ((ObjectNode) frame.get("params")).putNull("threadId");
        ((ObjectNode) frame.path("params").path("event")).put("type", type);
        return frame;
    }

    private ObjectNode eventFrame(String frameThreadId) {
        ObjectNode frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "event");
        ObjectNode params = frame.putObject("params");
        params.put("threadId", frameThreadId);
        params.putObject("event").put("type", "turn_end");
        return frame;
    }

    @Test
    void fetchContextRoundTrip() {
        ObjectNode callback = mapper.createObjectNode();
        callback.put("jsonrpc", "2.0");
        callback.put("id", 9999L);
        callback.put("method", "fetch_context");
        ObjectNode params = callback.putObject("params");
        params.put("threadId", threadId.toString());
        params.put("path", "inputs/context/workspace.json");

        sandbox.pushFrame(callback);

        // Server-side response sent back to runner.
        JsonNode response = sandbox.takeFrame();
        assertThat(response.get("id").asLong()).isEqualTo(9999L);
        assertThat(response.get("result").get("content").get("ok").asBoolean()).isTrue();
        var fetchContext = lastFetchContext.get();
        org.junit.jupiter.api.Assertions.assertNotNull(fetchContext);
        assertThat(fetchContext.path()).isEqualTo("inputs/context/workspace.json");
    }

    @Test
    void fetchContextRoundTrip_preservesStringIds() {
        // Regression: an earlier impl coerced frame.get("id").asLong() → 0 for any non-numeric
        // id, then echoed back `id: 0` which the runner's pendingFetchContexts (keyed by the
        // original string) never matched. Result: every `fetch_context` LLM tool call hung
        // until the runner's 10s timeout fired.
        String callbackId = "fc-" + UUID.randomUUID();
        ObjectNode callback = mapper.createObjectNode();
        callback.put("jsonrpc", "2.0");
        callback.put("id", callbackId);
        callback.put("method", "fetch_context");
        ObjectNode params = callback.putObject("params");
        params.put("threadId", threadId.toString());
        params.put("path", "inputs/context/workspace.json");

        sandbox.pushFrame(callback);

        JsonNode response = sandbox.takeFrame();
        assertThat(response.get("id").isString()).as("id must remain a string").isTrue();
        assertThat(response.get("id").asString()).isEqualTo(callbackId);
        assertThat(response.get("result").get("content").get("ok").asBoolean()).isTrue();
    }

    @Test
    void fetchContextForDifferentThread_isIgnored() {
        ObjectNode callback = mapper.createObjectNode();
        callback.put("jsonrpc", "2.0");
        callback.put("id", "fc-" + UUID.randomUUID());
        callback.put("method", "fetch_context");
        ObjectNode params = callback.putObject("params");
        params.put("threadId", UUID.randomUUID().toString());
        params.put("path", "inputs/context/workspace.json");

        sandbox.pushFrame(callback);

        assertThat(lastFetchContext.get()).isNull();
        sandbox.assertNoSentFrame();
    }

    @Test
    void fetchContextErrorRoundTrip_preservesStringIds() {
        String callbackId = "fc-" + UUID.randomUUID();
        ObjectNode callback = mapper.createObjectNode();
        callback.put("jsonrpc", "2.0");
        callback.put("id", callbackId);
        callback.put("method", "fetch_context");
        callback.putObject("params"); // missing required fields → -32600

        sandbox.pushFrame(callback);

        JsonNode response = sandbox.takeFrame();
        assertThat(response.get("id").isString()).isTrue();
        assertThat(response.get("id").asString()).isEqualTo(callbackId);
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32600);
    }

    // helpers

    private ObjectNode responseOf(long id, JsonNode result) {
        ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.set("result", result);
        return out;
    }

    /** Minimal AttachedSandbox stub: queue of sent frames + push API for the test driver. */
    static final class FakeSandbox implements AttachedSandbox {

        private final UUID sessionId = UUID.randomUUID();
        private final LinkedBlockingDeque<JsonNode> sentFrames = new LinkedBlockingDeque<>();
        private final CopyOnWriteArrayList<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
        volatile Runnable onLost = () -> {};

        @Override
        public SandboxIdentity identity() {
            return new SandboxIdentity(sessionId, "u1", "w1");
        }

        @Override
        public void send(JsonNode frame) throws InteractiveSandboxException {
            sentFrames.add(frame);
        }

        @Override
        public Disposable subscribe(Consumer<JsonNode> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public Disposable subscribeFromNow(Consumer<JsonNode> listener, Runnable onLost) {
            this.onLost = onLost;
            return subscribe(listener);
        }

        @Override
        public Instant lastActivityAt() {
            return Instant.now();
        }

        @Override
        public Duration idleFor() {
            return Duration.ZERO;
        }

        @Override
        public void close(Duration graceTimeout) {
            listeners.clear();
        }

        JsonNode takeFrame() {
            try {
                JsonNode frame = sentFrames.pollFirst(2, TimeUnit.SECONDS);
                if (frame == null) {
                    throw new AssertionError("Expected a sent frame within 2s");
                }
                return frame;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for frame", e);
            }
        }

        void pushFrame(JsonNode frame) {
            for (Consumer<JsonNode> listener : listeners) {
                listener.accept(frame);
            }
        }

        void assertNoSentFrame() {
            try {
                JsonNode frame = sentFrames.pollFirst(150, TimeUnit.MILLISECONDS);
                assertThat(frame).as("no frame should be sent").isNull();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for absence of frame", e);
            }
        }
    }
}
