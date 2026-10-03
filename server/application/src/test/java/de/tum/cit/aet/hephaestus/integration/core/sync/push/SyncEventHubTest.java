package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class SyncEventHubTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long WORKSPACE_ID = 1L;
    private static final long CONNECTION_ID = 10L;

    private final List<RecordingEmitter> createdEmitters = Collections.synchronizedList(new ArrayList<>());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private @Nullable SyncEventHub hub;

    private SyncEventHub newHub(Duration coalesceWindow) {
        return new SyncEventHub(MAPPER, meters, coalesceWindow, () -> {
            RecordingEmitter emitter = new RecordingEmitter();
            createdEmitters.add(emitter);
            return emitter;
        });
    }

    @AfterEach
    void tearDown() {
        if (hub != null) {
            hub.shutdown();
        }
    }

    @Test
    void subscribe_publish_deliversHintAsNamedSseEvent() {
        hub = newHub(Duration.ofMillis(10));
        hub.subscribe(WORKSPACE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        hub.publish(WORKSPACE_ID, new SyncEventHint("job", CONNECTION_ID));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.dataFrames()).hasSize(1));
        assertThat(emitter.eventNames()).containsExactly("sync");
        assertThat(emitter.dataFrames().get(0)).contains("\"scope\":\"job\"").contains("\"connectionId\":10");
    }

    @Test
    void sendFailure_deregistersAndCompletesEmitter() {
        hub = newHub(Duration.ofMillis(10));
        hub.subscribe(WORKSPACE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);
        emitter.failOnNextSend();

        hub.publish(WORKSPACE_ID, new SyncEventHint("job", CONNECTION_ID));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.completed()).isTrue());
        assertThat(hub.subscriberCount(WORKSPACE_ID)).isZero();
        assertThat(counter("integration.sync.sse.events", "outcome", "error")).isEqualTo(1.0);
    }

    @Test
    void completion_deregistersSubscriber() {
        hub = newHub(Duration.ofMillis(10));
        hub.subscribe(WORKSPACE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);
        assertThat(hub.subscriberCount(WORKSPACE_ID)).isEqualTo(1);

        emitter.fireCompletion();

        assertThat(hub.subscriberCount(WORKSPACE_ID)).isZero();
    }

    @Test
    void trailingEdgeCoalescer_deliversOnlyTheLastHintInTheWindow() {
        hub = newHub(Duration.ofMillis(150));
        hub.subscribe(WORKSPACE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        hub.publish(WORKSPACE_ID, new SyncEventHint("job", CONNECTION_ID));
        hub.publish(WORKSPACE_ID, new SyncEventHint("job", CONNECTION_ID));
        hub.publish(WORKSPACE_ID, new SyncEventHint("job", CONNECTION_ID));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.dataFrames()).hasSize(1));
        assertThat(emitter.dataFrames().get(0)).contains("\"scope\":\"job\"");
    }

    @Test
    void trailingEdgeCoalescer_differentScopesAreNotCoalescedTogether() {
        hub = newHub(Duration.ofMillis(150));
        hub.subscribe(WORKSPACE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        hub.publish(WORKSPACE_ID, new SyncEventHint("job", CONNECTION_ID));
        hub.publish(WORKSPACE_ID, new SyncEventHint("resources", CONNECTION_ID));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.dataFrames()).hasSize(2));
        assertThat(emitter.dataFrames())
                .anySatisfy(frame -> assertThat(frame).contains("\"scope\":\"job\""))
                .anySatisfy(frame -> assertThat(frame).contains("\"scope\":\"resources\""));
    }

    @Test
    void heartbeat_sendsPingToAllActiveSubscribers() {
        hub = newHub(Duration.ofMillis(10));
        hub.subscribe(WORKSPACE_ID);
        hub.subscribe(WORKSPACE_ID);
        RecordingEmitter first = createdEmitters.get(0);
        RecordingEmitter second = createdEmitters.get(1);

        hub.sendHeartbeats();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(first.comments()).contains("ping"));
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(second.comments()).contains("ping"));
    }

    @Test
    void shutdown_completesAndDeregistersActiveSubscribers() {
        hub = newHub(Duration.ofMillis(10));
        hub.subscribe(WORKSPACE_ID);
        hub.subscribe(WORKSPACE_ID);

        hub.shutdown();

        assertThat(createdEmitters)
                .allSatisfy(emitter -> assertThat(emitter.completed()).isTrue());
        assertThat(hub.subscriberCount(WORKSPACE_ID)).isZero();
        assertThat(meters.get("integration.sync.sse.subscribers").gauge().value())
                .isZero();
    }

    @Test
    void subscribe_sendsInitialConnectedCommentImmediately() {
        hub = newHub(Duration.ofMillis(10));
        hub.subscribe(WORKSPACE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);

        assertThat(emitter.comments()).containsExactly("connected");
        assertThat(hub.subscriberCount(WORKSPACE_ID)).isEqualTo(1);
    }

    @Test
    void subscribe_beyondPerWorkspaceCap_rejectsNewSubscriber() {
        SyncEventHub currentHub = newHub(Duration.ofMillis(10));
        hub = currentHub;
        for (int i = 0; i < 20; i++) {
            currentHub.subscribe(WORKSPACE_ID);
        }

        assertThatThrownBy(() -> currentHub.subscribe(WORKSPACE_ID)).isInstanceOf(ResponseStatusException.class);
        assertThat(currentHub.subscriberCount(WORKSPACE_ID)).isEqualTo(20);
        assertThat(counter("integration.sync.sse.subscriptions", "outcome", "accepted"))
                .isEqualTo(20.0);
        assertThat(counter("integration.sync.sse.subscriptions", "outcome", "rejected"))
                .isEqualTo(1.0);
        assertThat(meters.get("integration.sync.sse.subscribers").gauge().value())
                .isEqualTo(20.0);
    }

    @Test
    void concurrentSubscriptions_neverExceedPerWorkspaceCap() throws Exception {
        SyncEventHub currentHub = newHub(Duration.ofMillis(10));
        hub = currentHub;
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 100; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        currentHub.subscribe(WORKSPACE_ID);
                    } catch (ResponseStatusException expected) {
                        rejected.incrementAndGet();
                    }
                }));
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        for (Future<?> future : futures) {
            future.get();
        }

        assertThat(hub.subscriberCount(WORKSPACE_ID)).isEqualTo(20);
        assertThat(rejected).hasValue(80);
    }

    @Test
    void blockedHeartbeat_doesNotQueueAdditionalHeartbeatTasks() throws Exception {
        hub = newHub(Duration.ofMillis(10));
        hub.subscribe(WORKSPACE_ID);
        RecordingEmitter emitter = createdEmitters.get(0);
        emitter.blockHeartbeat();

        hub.sendHeartbeats();
        assertThat(emitter.awaitBlockedHeartbeat()).isTrue();
        for (int i = 0; i < 20; i++) {
            hub.sendHeartbeats();
        }
        emitter.releaseHeartbeat();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.comments()).contains("ping"));
        assertThat(emitter.comments().stream().filter("ping"::equals)).hasSize(1);
    }

    private double counter(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).counter().count();
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> dataFrames = new ArrayList<>();
        private final List<String> eventNames = new ArrayList<>();
        private final List<String> comments = new ArrayList<>();
        private final AtomicReference<Runnable> completionCallback = new AtomicReference<>();
        private final CountDownLatch heartbeatBlocked = new CountDownLatch(1);
        private final CountDownLatch releaseHeartbeat = new CountDownLatch(1);
        private volatile boolean completed;
        private volatile boolean failOnNextSend;
        private volatile boolean blockHeartbeat;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (failOnNextSend) {
                failOnNextSend = false;
                throw new IOException("simulated socket close");
            }
            StringBuilder wire = new StringBuilder();
            for (var entry : builder.build()) {
                wire.append(entry.getData().toString());
            }
            if (blockHeartbeat && wire.toString().contains(":ping")) {
                heartbeatBlocked.countDown();
                try {
                    releaseHeartbeat.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            for (String line : wire.toString().split("\n")) {
                if (line.startsWith("data:")) {
                    dataFrames.add(line.substring("data:".length()));
                } else if (line.startsWith("event:")) {
                    eventNames.add(line.substring("event:".length()));
                } else if (line.startsWith(":")) {
                    comments.add(line.substring(1));
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallback.set(callback);
        }

        @Override
        public void onTimeout(Runnable callback) {}

        @Override
        public void onError(Consumer<Throwable> callback) {}

        void fireCompletion() {
            Runnable callback = completionCallback.get();
            if (callback != null) callback.run();
        }

        void failOnNextSend() {
            this.failOnNextSend = true;
        }

        void blockHeartbeat() {
            blockHeartbeat = true;
        }

        boolean awaitBlockedHeartbeat() throws InterruptedException {
            return heartbeatBlocked.await(2, TimeUnit.SECONDS);
        }

        void releaseHeartbeat() {
            releaseHeartbeat.countDown();
        }

        synchronized List<String> dataFrames() {
            return List.copyOf(dataFrames);
        }

        synchronized List<String> eventNames() {
            return List.copyOf(eventNames);
        }

        synchronized List<String> comments() {
            return List.copyOf(comments);
        }

        boolean completed() {
            return completed;
        }
    }
}
