package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.NatsSubscriptionInfo;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.StreamSubscription;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Two server replicas against one real JetStream server, bound to the same durable consumers as two server Pods are.
 * A scope's events are handled one at a time and in stream order, whichever replica receives them, also when a handler
 * outlasts the ack wait. A replay runs only while no replica handles a live event of the scope. A handler that never
 * ends gives its event back after the handler timeout.
 */
@Tag("integration")
class ServerReplicasConsumerIntegrationTest {

    private static final String STREAM = "github";
    private static final long SCOPE_ID = 31L;
    private static final String DURABLE = "replicas";
    private static final String SUBJECT = "github.acme.replicas.issues";
    /** Shorter than the slow handler below, so the broker would redeliver its message without an in-progress ack. */
    private static final Duration ACK_WAIT = Duration.ofSeconds(1);
    /** Longer than every handler these tests release, shorter than one they never release. */
    private static final Duration HANDLER_TIMEOUT = Duration.ofSeconds(5);

    private final List<String> handled = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger mostRunning = new AtomicInteger();
    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();
    private final List<String> interrupted = new CopyOnWriteArrayList<>();

    private Connection natsConnection;
    private JetStreamManagement jsm;
    private final IntegrationConsumerStats firstStats = new IntegrationConsumerStats();
    private IntegrationNatsConsumer first;
    private IntegrationNatsConsumer second;

    @BeforeEach
    void setUp() throws Exception {
        natsConnection = Nats.connect(NatsTestContainer.getServerUrl());
        jsm = natsConnection.jetStreamManagement();
        // A receiver that an earlier test class booted leaves a stream of this name with its own configuration.
        if (jsm.getStreamNames().contains(STREAM)) jsm.deleteStream(STREAM);
        jsm.addStream(StreamConfiguration.builder()
                .name(STREAM)
                .subjects(STREAM + ".>")
                .storageType(StorageType.Memory)
                .build());
        first = replica(firstStats);
        second = replica(new IntegrationConsumerStats());
        first.startConsumingScope(SCOPE_ID);
        second.startConsumingScope(SCOPE_ID);
        await().atMost(Duration.ofSeconds(10)).until(() -> attached(first) && attached(second));
    }

    @AfterEach
    void tearDown() throws Exception {
        holds.values().forEach(CountDownLatch::countDown);
        first.shutdown();
        second.shutdown();
        jsm.deleteStream(STREAM);
        natsConnection.close();
    }

    @Test
    void shouldHandleAScopesEventsOneAtATimeInStreamOrderAcrossReplicas() throws Exception {
        List<String> published =
                IntStream.range(0, 6).mapToObj(index -> "event-" + index).toList();
        // The first handler runs for more than twice the ack wait.
        holds.put("event-0", new CountDownLatch(1));
        for (String event : published) publish(event);

        assertThat(entered("event-0").await(10, TimeUnit.SECONDS)).isTrue();
        TimeUnit.MILLISECONDS.sleep(ACK_WAIT.multipliedBy(5).toMillis() / 2);
        holds.get("event-0").countDown();

        await().atMost(Duration.ofSeconds(20)).until(() -> handled.size() >= published.size());
        assertThat(handled).containsExactlyElementsOf(published);
        assertThat(mostRunning).hasValue(1);
        assertThat(deliveries)
                .allSatisfy((event, count) -> assertThat(count).as(event).hasValue(1));
    }

    @Test
    void shouldReplayOnlyWhileNoReplicaHandlesALiveEvent() throws Exception {
        holds.put("live", new CountDownLatch(1));
        publish("live");
        assertThat(entered("live").await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch replayRunning = new CountDownLatch(1);
        CountDownLatch finishReplay = new CountDownLatch(1);
        FutureTask<String> replay = new FutureTask<>(() -> second.exclusive(SCOPE_ID, STREAM, () -> {
            replayRunning.countDown();
            awaitUninterruptibly(finishReplay);
            return "replayed";
        }));
        Thread.ofVirtual().start(replay);

        // The live event holds its message, so the replay waits.
        assertThat(replayRunning.await(1, TimeUnit.SECONDS)).isFalse();
        holds.get("live").countDown();
        assertThat(replayRunning.await(10, TimeUnit.SECONDS)).isTrue();

        // A live event that arrives while the replay runs waits for it.
        publish("after-replay");
        TimeUnit.SECONDS.sleep(1);
        assertThat(handled).containsExactly("live");
        finishReplay.countDown();
        assertThat(replay.get(10, TimeUnit.SECONDS)).isEqualTo("replayed");

        // A paused consumer serves no pull request, so the next live event waits for the replicas' next pull.
        await().atMost(Duration.ofSeconds(45)).until(() -> handled.size() >= 2);
        assertThat(handled).containsExactly("live", "after-replay");
    }

    @Test
    void shouldGiveUpAHungHandlerAndHandleItsEventAgainWhenTheTimeoutPasses() throws Exception {
        publish("hung");
        publish("after");

        await().atMost(Duration.ofSeconds(30)).until(() -> handled.size() >= 2);
        assertThat(handled).containsExactly("hung", "after");
        assertThat(interrupted).containsExactly("hung");
        assertThat(deliveries.get("hung")).hasValue(2);
    }

    @Test
    void shouldRecordThatTheBrokerMeetsTheMinimumVersion() {
        assertThat(firstStats.brokerVersion())
                .hasValueSatisfying(broker -> assertThat(broker.supported()).isTrue());
    }

    @Test
    void shouldReplayAtOnceWhenTheConsumerDoesNotExist() {
        assertThat(first.exclusive(SCOPE_ID + 1, STREAM, () -> "replayed")).isEqualTo("replayed");
    }

    private IntegrationNatsConsumer replica(IntegrationConsumerStats stats) {
        var defaults = NatsConsumerPropertiesFixture.defaults();
        IntegrationNatsConsumer replica = new IntegrationNatsConsumer(
                new NatsConnectionProperties(
                        true,
                        NatsTestContainer.getServerUrl(),
                        DURABLE,
                        new NatsConnectionProperties.Consumer(Duration.ofSeconds(10))),
                new NatsConsumerProperties(
                        ACK_WAIT,
                        HANDLER_TIMEOUT,
                        defaults.reconnectDelay(),
                        defaults.inactiveThreshold(),
                        defaults.poison()),
                scopeId -> Optional.of(new NatsSubscriptionInfo(
                        scopeId,
                        List.of(new StreamSubscription(
                                STREAM, Set.of(ConsumerSubjectMath.repositoryFilter(STREAM, "acme/replicas")))))),
                dispatcher(),
                mock(IntegrationPoisonHandler.class),
                stats,
                mock(ConnectionActivityRecorder.class),
                List.of(),
                NatsTraceContexts.none(),
                NatsTraceContexts.messageMeters(),
                DeliveryInboxes.passthrough(),
                event -> {});
        replica.onApplicationReady();
        return replica;
    }

    private IntegrationMessageDispatcher dispatcher() {
        IntegrationMessageHandler handler = new IntegrationMessageHandler() {
            @Override
            public EventTypeKey key() {
                return new EventTypeKey(IntegrationKind.GITHUB, "issues");
            }

            @Override
            public void onMessage(Message msg) {
                String event = new String(msg.getData(), StandardCharsets.UTF_8);
                deliveries
                        .computeIfAbsent(event, ignored -> new AtomicInteger())
                        .incrementAndGet();
                mostRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    entered(event).countDown();
                    if (event.equals("hung") && msg.metaData().deliveredCount() == 1) {
                        // Never released: only the handler timeout ends it.
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException stopped) {
                            interrupted.add(event);
                            return;
                        }
                    }
                    CountDownLatch hold = holds.get(event);
                    if (hold != null) awaitUninterruptibly(hold);
                    handled.add(event);
                } finally {
                    running.decrementAndGet();
                }
            }
        };
        IntegrationMessageDispatcher dispatcher = mock(IntegrationMessageDispatcher.class);
        when(dispatcher.dispatch(anyString())).thenReturn(Optional.of(handler));
        return dispatcher;
    }

    private CountDownLatch entered(String event) {
        return entered.computeIfAbsent(event, ignored -> new CountDownLatch(1));
    }

    private void publish(String event) throws Exception {
        natsConnection.jetStream().publish(SUBJECT, event.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean attached(IntegrationNatsConsumer replica) {
        return replica.trackedConsumers(SCOPE_ID).stream().anyMatch(ScopeConsumer::isAttached);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
