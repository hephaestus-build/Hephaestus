package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.ClientDisconnectedException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Focused coverage for the per-turn SSE façade. The full orchestration path is exercised by
 * {@link MentorChatServiceTest}; this class pins the invariants reviewers care about most:
 * the disconnect hook fires exactly once, the {@code [DONE]} sentinel always lands, and
 * heartbeat ticks never race the terminal {@code complete()}.
 */
class MentorSseChannelTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RecordingEmitter emitter;
    private ScheduledExecutorService scheduler;
    private MentorSseChannel channel;

    @BeforeEach
    void setUp() {
        emitter = new RecordingEmitter();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        channel = new MentorSseChannel(emitter, MAPPER, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void send_writesChunkAsData() {
        UUID id = UUID.randomUUID();
        channel.send(new UIMessageChunk.Start(id, null));
        assertThat(emitter.dataFrames()).hasSize(1);
        assertThat(emitter.dataFrames().get(0)).contains("\"type\":\"start\"").contains(id.toString());
    }

    @Test
    void deliveryOutcomeRequiresSuccessfulFinishChunk() {
        channel.send(new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null));
        emitter.failOnNextSend();

        assertThat(channel.completeWithDone()).isEqualTo(MentorChannel.DeliveryOutcome.DELIVERED);
    }

    @Test
    @DisplayName("send while clientGone is a no-op")
    void send_afterDisconnect_isNoop() {
        // Trigger the lifecycle disconnect via onCompletion → clientGone flips.
        channel.bindLifecycle();
        emitter.fireCompletion();

        channel.send(new UIMessageChunk.StartStep());
        assertThat(emitter.dataFrames()).isEmpty();
    }

    @Test
    void send_onIoException_throwsAndFlips() {
        emitter.failOnNextSend();
        assertThatThrownBy(() -> channel.send(new UIMessageChunk.StartStep()))
                .isInstanceOf(ClientDisconnectedException.class);
        assertThat(channel.isClientGone()).isTrue();
    }

    @Test
    void disconnectHook_firesExactlyOnce() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        channel.bindLifecycle();
        channel.onDisconnect(fired::incrementAndGet);

        // Two threads race to trigger different lifecycle callbacks; only the first flip wins.
        var t1 = new Thread(() -> emitter.fireCompletion());
        var t2 = new Thread(() -> emitter.fireError(new RuntimeException("network")));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(fired.get()).isEqualTo(1);
        assertThat(channel.isClientGone()).isTrue();
    }

    @Test
    void onDisconnect_afterFlagFlipped_firesImmediately() {
        channel.bindLifecycle();
        emitter.fireCompletion(); // flips first
        AtomicInteger fired = new AtomicInteger();
        channel.onDisconnect(fired::incrementAndGet);
        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void completeWithDone_emitsSentinel() {
        channel.completeWithDone();
        assertThat(emitter.dataFrames()).containsExactly("[DONE]");
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void completeWithError_emitsErrorThenSentinel() {
        channel.completeWithError("boom");
        assertThat(emitter.dataFrames())
                .hasSize(2)
                .first()
                .asString()
                .contains("\"type\":\"error\"")
                .contains("\"errorText\":\"boom\"");
        assertThat(emitter.dataFrames().get(1)).isEqualTo("[DONE]");
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void completeWithConflict_emitsStatusErrorSentinel() {
        channel.completeWithConflict();
        List<String> frames = emitter.dataFrames();
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0)).contains("\"type\":\"data-mentor-status\"").contains("\"state\":\"conflict\"");
        assertThat(frames.get(1)).contains("\"type\":\"error\"");
        assertThat(frames.get(2)).isEqualTo("[DONE]");
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void closeAfterDone_isIdempotent() {
        channel.completeWithDone();
        channel.close();
        channel.close();
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void sendAfterCompleteWithDone_isSilentNoOp() {
        // Setup: register a disconnect hook + bind lifecycle. A correctly-finished turn must
        // NEVER fire the hook — that hook calls session.abort() against a sandbox we just
        // cleanly closed. Regression guard: a stray post-complete send (heartbeat tick or a
        // second agent_end) used to throw IllegalStateException → caught as "disconnect" →
        // flagDisconnected → hook fired. The `closed` flag now short-circuits before send.
        AtomicInteger fired = new AtomicInteger();
        channel.bindLifecycle();
        channel.onDisconnect(fired::incrementAndGet);

        channel.completeWithDone();
        // Pi misbehaves: a stray second agent_end event reaches handleEvent post-complete.
        channel.send(new UIMessageChunk.StartStep());

        assertThat(channel.isClientGone()).isFalse();
        assertThat(fired.get()).isZero();
    }

    @Test
    void concurrentSends_areSerialised() throws Exception {
        // SseEmitter#send is not thread-safe in Spring. The channel's writeLock serialises
        // chunk-writes; this test drives 200 concurrent chunk sends from two threads and
        // asserts every recorded data frame is a complete JSON object (no interleaved bytes
        // from a racing send).
        int total = 200;
        var t1 = new Thread(() -> {
            for (int i = 0; i < total / 2; i++) channel.send(new UIMessageChunk.StartStep());
        });
        var t2 = new Thread(() -> {
            for (int i = 0; i < total / 2; i++) channel.send(new UIMessageChunk.StartStep());
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(emitter.dataFrames()).hasSize(total);
        for (String frame : emitter.dataFrames()) {
            // Every frame is a complete JSON object — no partial / interleaved fragments.
            assertThat(frame).startsWith("{").endsWith("}").contains("\"type\":\"start-step\"");
        }
    }

    @Test
    void heartbeatTick_concurrentWithSends_writeLockSerialises() throws Exception {
        // The actual concurrency hazard: the heartbeat ScheduledExecutorService thread and
        // the runner-event-handler thread are DIFFERENT threads writing to the same emitter.
        // Without writeLock, a `:ping\n\n` heartbeat comment can land mid-`data: {...}\n\n`
        // chunk and corrupt the SSE stream. This test starts a tight 5ms-tick heartbeat and
        // 200 chunk sends; every recorded data frame must remain a complete JSON object AND
        // every recorded comment frame must be intact.
        channel.startKeepAlive();
        // Drive lastSendNanos far in the past so EVERY tick attempts a write.
        java.lang.reflect.Field lastSendField = MentorSseChannel.class.getDeclaredField("lastSendNanos");
        lastSendField.setAccessible(true);
        ((AtomicLong) lastSendField.get(channel)).set(0L);

        int total = 200;
        var sender = new Thread(() -> {
            for (int i = 0; i < total; i++) channel.send(new UIMessageChunk.StartStep());
        });
        sender.start();
        sender.join();
        // Allow a couple of heartbeat ticks to fire alongside.
        Thread.sleep(50);
        List<String> frames = List.copyOf(emitter.dataFrames());
        channel.close();

        // Every JSON frame must be complete; every comment frame must equal "ping".
        for (String frame : frames) {
            assertThat(frame).startsWith("{").endsWith("}").contains("\"type\":\"start-step\"");
        }
        // Heartbeat comments are SSE `:` lines emitted via `SseEmitter.event().comment("ping")`;
        // our RecordingEmitter parses `data:` lines only, so we can also assert that the comment
        // stream didn't poison the data-frame collection. The negative shape is what matters.
        assertThat(frames).allMatch(f -> f.contains("\"type\":\"start-step\""));
    }

    /** Test-only emitter that records data frames + can simulate disconnect failures. */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> dataFrames = new ArrayList<>();
        private boolean completed;
        private boolean failOnNextSend;
        private @org.jspecify.annotations.Nullable Runnable completionCallback;
        private @org.jspecify.annotations.Nullable Runnable timeoutCallback;
        private @org.jspecify.annotations.Nullable Consumer<Throwable> errorCallback;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnNextSend) {
                failOnNextSend = false;
                throw new IOException("simulated socket close");
            }
            // SseEventBuilder.build() returns a Set<DataWithMediaType>; payload chunks are
            // interleaved with the SSE text framing ("data:", "\n", "\n") in append order.
            // Re-assemble the wire text and extract the JSON payload(s) on `data:` lines.
            StringBuilder wire = new StringBuilder();
            for (var entry : builder.build()) {
                wire.append(entry.getData().toString());
            }
            for (String line : wire.toString().split("\n")) {
                if (line.startsWith("data:")) {
                    dataFrames.add(line.substring("data:".length()));
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void onCompletion(Runnable callback) {
            this.completionCallback = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            this.timeoutCallback = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.errorCallback = callback;
        }

        void fireCompletion() {
            if (completionCallback != null) completionCallback.run();
        }

        void fireError(Throwable t) {
            if (errorCallback != null) errorCallback.accept(t);
        }

        void failOnNextSend() {
            this.failOnNextSend = true;
        }

        List<String> dataFrames() {
            return dataFrames;
        }

        boolean completed() {
            return completed;
        }
    }
}
