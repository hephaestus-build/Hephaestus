package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.NatsSubscriptionInfo;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.StreamSubscription;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import io.nats.client.ConsumerContext;
import io.nats.client.Message;
import io.nats.client.StreamContext;
import io.nats.client.api.DeliverPolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests for {@link IntegrationNatsConsumer}.
 *
 * <p>Focus is on the small set of pure helpers carved out of the connect / dispatch path:
 *
 * <ul>
 *   <li>{@link IntegrationNatsConsumer#reconnectBackoffMs(int)} — full-jitter
 *       exponential backoff used by the connect-with-retry loop. Bounds + monotonicity
 *       under attempt growth are nailed down so future tuning doesn't accidentally drop
 *       sub-second delays under load.</li>
 * </ul>
 *
 * <p>The orchestrator itself is exercised indirectly by the workspace-lifecycle tests
 * ({@code GitLabWorkspaceInitializationServiceTest}) and the JetStream loop is covered
 * by the existing {@code IntegrationPoisonHandlerTest} +
 * {@code IntegrationMessageDispatcherTest}. A full Spring-context test would re-test
 * the JetStream client, which is out of scope here.
 */
@Tag("unit")
class IntegrationNatsConsumerTest {

    @Test
    void purgeFenceRejectsQueuedConsumerRestart() {
        NatsSubscriptionProvider subscriptions = mock(NatsSubscriptionProvider.class);
        IntegrationNatsConsumer consumer = new IntegrationNatsConsumer(
                new NatsConnectionProperties(
                        true,
                        "nats://localhost:4222",
                        "heph",
                        new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))),
                NatsConsumerPropertiesFixture.defaults(),
                subscriptions,
                mock(IntegrationMessageDispatcher.class),
                mock(IntegrationPoisonHandler.class),
                new IntegrationConsumerStats(),
                mock(ConnectionActivityRecorder.class),
                List.of());
        try {
            consumer.stopConsumingScopeForPurge(7L);
            consumer.startConsumingScope(7L);

            verifyNoInteractions(subscriptions);
        } finally {
            consumer.shutdown();
        }
    }

    @Test
    void rolledBackPurgeRestartsConsumption() throws Exception {
        CountDownLatch restarted = new CountDownLatch(1);
        IntegrationNatsConsumer consumer =
                new IntegrationNatsConsumer(
                        new NatsConnectionProperties(
                                true,
                                "nats://localhost:4222",
                                "heph",
                                new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))),
                        NatsConsumerPropertiesFixture.defaults(),
                        scopeId -> Optional.empty(),
                        mock(IntegrationMessageDispatcher.class),
                        mock(IntegrationPoisonHandler.class),
                        new IntegrationConsumerStats(),
                        mock(ConnectionActivityRecorder.class),
                        List.of()) {
                    @Override
                    void ensureNatsConnectionEstablished() {}

                    @Override
                    void reconcileScope(Long scopeId) {
                        restarted.countDown();
                    }
                };
        TransactionSynchronizationManager.initSynchronization();
        try {
            consumer.stopConsumingScopeForPurge(7L);
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            assertThat(restarted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            consumer.shutdown();
        }
    }

    @Test
    void newConsumerStartsAtNewMessagesInsteadOfReplayingStreamHistory() {
        NatsConsumerProperties properties = NatsConsumerPropertiesFixture.defaults();

        var config =
                IntegrationNatsConsumer.newConsumerConfiguration(new String[] {"slack.>"}, properties, "heph-slack");

        assertThat(config.getDeliverPolicy()).isEqualTo(DeliverPolicy.New);
        assertThat(config.getDurable()).isEqualTo("heph-slack");
    }

    @Test
    void everyDurableCarriesAnInactiveThresholdUnderShippedDefaults() {
        // Every durable, not only the ones a deployment remembers to configure — see
        // NatsConsumerProperties for why an unreaped one is permanent.
        var config = IntegrationNatsConsumer.newConsumerConfiguration(
                new String[] {"github.>"}, NatsConsumerPropertiesFixture.defaults(), "heph-github");

        assertThat(config.getInactiveThreshold()).isNotNull().isPositive();
    }

    @Test
    void shouldReapDurableWhenInactiveThresholdIsConfigured() {
        var config = IntegrationNatsConsumer.newConsumerConfiguration(
                new String[] {"github.>"},
                NatsConsumerPropertiesFixture.withInactiveThreshold(Duration.ofHours(72)),
                "heph-github");

        assertThat(config.getInactiveThreshold()).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void shouldLeaveEphemeralLifetimeAloneWhenInactiveThresholdIsConfigured() {
        var config = IntegrationNatsConsumer.newConsumerConfiguration(
                new String[] {"github.>"},
                NatsConsumerPropertiesFixture.withInactiveThreshold(Duration.ofHours(72)),
                null);

        assertThat(config.getInactiveThreshold()).isNull();
    }

    @Nested
    class ReconnectBackoffMs {

        @ParameterizedTest(name = "attempt={0} → delay in [{1}, {2}] ms")
        @CsvSource({
            // attempt, minMs (= base * 2^attempt, no jitter floor), maxMs (= base * 2^attempt + JITTER_MAX, capped at
            // 30_000)
            "0, 1000, 2000",
            "1, 2000, 3000",
            "2, 4000, 5000",
            "3, 8000, 9000",
            "4, 16000, 17000",
            "5, 30000, 30000",
            "6, 30000, 30000",
        })
        void clampedExponentialWithJitter(int attempt, long minExpected, long maxExpected) {
            long delay = IntegrationNatsConsumer.reconnectBackoffMs(attempt);
            assertThat(delay)
                    .as("attempt=%d expected to land in [%d, %d] ms (got %d)", attempt, minExpected, maxExpected, delay)
                    .isGreaterThanOrEqualTo(minExpected)
                    .isLessThanOrEqualTo(maxExpected);
        }

        @Test
        void negativeAttemptClampedToZero() {
            long delay = IntegrationNatsConsumer.reconnectBackoffMs(-5);
            // base=1000ms, jitter up to 1000ms → [1000, 2000]
            assertThat(delay).isBetween(1_000L, 2_000L);
        }

        @Test
        void delayClampedToHardMax() {
            // Try the same call many times to stress the random-jitter path.
            for (int i = 0; i < 1000; i++) {
                long delay = IntegrationNatsConsumer.reconnectBackoffMs(100);
                assertThat(delay).isLessThanOrEqualTo(30_000L);
            }
        }
    }

    /** A message on an authenticated route is handled only when its admission passes, after it is durable. */
    @Nested
    class RouteAdmissionHook {

        private static final String SUBJECT = "gitlab.?connection.5.issue";

        private final IntegrationMessageDispatcher dispatcher = mock(IntegrationMessageDispatcher.class);
        private final IntegrationPoisonHandler poisonHandler = mock(IntegrationPoisonHandler.class);
        private final IntegrationMessageHandler handler = mock(IntegrationMessageHandler.class);
        private final Message message = mock(Message.class);

        private IntegrationNatsConsumer consumer(RouteAdmission admission) {
            when(message.getSubject()).thenReturn(SUBJECT);
            when(handler.key()).thenReturn(new EventTypeKey(IntegrationKind.GITLAB, "issue"));
            when(dispatcher.dispatch(SUBJECT)).thenReturn(Optional.of(handler));
            return new IntegrationNatsConsumer(
                    new NatsConnectionProperties(
                            true,
                            "nats://localhost:4222",
                            "heph",
                            new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))),
                    NatsConsumerPropertiesFixture.withFastPoisonBackoff(),
                    scopeId -> Optional.empty(),
                    dispatcher,
                    poisonHandler,
                    new IntegrationConsumerStats(),
                    mock(ConnectionActivityRecorder.class),
                    List.of(admission));
        }

        @Test
        void shouldAcknowledgeWithoutHandlingWhenRouteIsNotAdmitted() {
            consumer(new FixedAdmission(false, null)).handleMessage(5L, message);

            verify(handler, never()).onMessage(message);
            verify(message).ack();
        }

        @Test
        void shouldRedeliverWhenAdmissionCannotBeDecided() {
            consumer(new FixedAdmission(true, new IllegalStateException("database unavailable")))
                    .handleMessage(5L, message);

            verify(handler, never()).onMessage(message);
            verify(message, never()).ack();
            verify(poisonHandler).nakWithBackoff(message);
        }

        @Test
        void shouldHandleWithinTheAdmission() {
            consumer(new FixedAdmission(true, null)).handleMessage(5L, message);

            verify(handler).onMessage(message);
            verify(message).ack();
        }

        private record FixedAdmission(
                boolean admitted, @Nullable RuntimeException failure) implements RouteAdmission {

            @Override
            public boolean owns(String subject) {
                return subject.startsWith("gitlab.?connection.");
            }

            @Override
            public boolean admit(@Nullable Long scopeId, Message msg, Runnable handling) {
                if (failure != null) {
                    throw failure;
                }
                if (admitted) {
                    handling.run();
                }
                return admitted;
            }
        }
    }

    @Nested
    class ActivityRecorderHook {

        private static final Long SCOPE_ID = 7L;

        private final IntegrationMessageDispatcher dispatcher = mock(IntegrationMessageDispatcher.class);
        private final ConnectionActivityRecorder activityRecorder = mock(ConnectionActivityRecorder.class);
        private final Message message = mock(Message.class);
        private IntegrationNatsConsumer consumer = newConsumer();

        private IntegrationNatsConsumer newConsumer() {
            when(message.getSubject()).thenReturn("github.acme.repo.issues");
            return new IntegrationNatsConsumer(
                    new NatsConnectionProperties(
                            true,
                            "nats://localhost:4222",
                            "heph",
                            new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))),
                    NatsConsumerPropertiesFixture.withFastPoisonBackoff(),
                    scopeId -> Optional.empty(),
                    dispatcher,
                    mock(IntegrationPoisonHandler.class),
                    new IntegrationConsumerStats(),
                    activityRecorder,
                    List.of());
        }

        @Test
        void recordsActivityOnHandledMessageWithScope() {
            consumer = newConsumer();
            IntegrationMessageHandler handler = mock(IntegrationMessageHandler.class);
            EventTypeKey key = new EventTypeKey(IntegrationKind.GITHUB, "repository.issues");
            when(handler.key()).thenReturn(key);
            when(dispatcher.dispatch("github.acme.repo.issues")).thenReturn(Optional.of(handler));

            consumer.handleMessage(SCOPE_ID, message);

            verify(handler).onMessage(message);
            verify(message).ack();
            verify(activityRecorder).recordEventProcessed(SCOPE_ID, IntegrationKind.GITHUB, "repository.issues");
        }

        @Test
        void skipsRecorderWhenUnmatched() {
            consumer = newConsumer();
            when(dispatcher.dispatch("github.acme.repo.issues")).thenReturn(Optional.empty());

            consumer.handleMessage(SCOPE_ID, message);

            verify(message).ack();
            verifyNoInteractions(activityRecorder);
        }

        @Test
        void skipsRecorderWhenScopeIsNull() {
            consumer = newConsumer();
            IntegrationMessageHandler handler = mock(IntegrationMessageHandler.class);
            when(handler.key()).thenReturn(new EventTypeKey(IntegrationKind.GITHUB, "installation.created"));
            when(dispatcher.dispatch("github.acme.repo.issues")).thenReturn(Optional.of(handler));

            consumer.handleMessage(null, message);

            verify(handler).onMessage(message);
            verifyNoInteractions(activityRecorder);
        }
    }

    /**
     * A scope binds SEVERAL streams now (an SCM stream plus {@code outline}). If the second stream's consumer
     * cannot be created — the common case being that the {@code outline} stream does not exist yet because the
     * webhook pod creates it on ITS boot — the first stream's consumer has already been {@code start()}ed.
     *
     * <p>Two invariants:
     * <ol>
     *   <li>every started consumer is TRACKED, so it can still be stopped/updated — an untracked one runs
     *       forever, is invisible to {@code updateScopeConsumer} ("not running"), and gets duplicated on its
     *       own durable by the next start;</li>
     *   <li>the failure re-arms a retry, so the transient case self-heals rather than needing a restart.</li>
     * </ol>
     */
    @Nested
    class ReconcileScopePartialFailure {

        private static final Long SCOPE_ID = 42L;
        private static final String SCM_STREAM = "github";
        private static final String OUTLINE_STREAM = "outline";

        private @Nullable FakeFleet fleet;

        private FakeFleet fleetFailingOn(String... failingStreams) {
            fleet = new FakeFleet(Set.of(failingStreams));
            return fleet;
        }

        @AfterEach
        void tearDown() {
            if (fleet != null) {
                fleet.shutdown(); // stops the retry timer + consumer threads
            }
        }

        @Test
        @DisplayName(
                "a consumer started before the failing stream stays tracked (never a running-but-orphaned consumer)")
        void partialFailureCommitsWhatItStarted() {
            FakeFleet consumer = fleetFailingOn(OUTLINE_STREAM);

            assertThatThrownBy(() -> consumer.reconcileScope(SCOPE_ID)).isInstanceOf(IOException.class);

            assertThat(consumer.started).hasSize(1);
            assertThat(consumer.trackedConsumers(SCOPE_ID))
                    .as("the started consumer must remain reachable for stop/update")
                    .containsExactlyElementsOf(consumer.started);
            assertThat(consumer.trackedConsumers(SCOPE_ID).get(0).streamName()).isEqualTo(SCM_STREAM);
            assertThat(consumer.trackedConsumers(SCOPE_ID).get(0).isRunning()).isTrue();
        }

        @Test
        @DisplayName("a transient stream failure self-heals: the reconcile is retried and the missing consumer appears")
        void failedReconcileIsRetriedUntilItSucceeds() {
            FakeFleet consumer = fleetFailingOn(OUTLINE_STREAM);

            assertThatThrownBy(() -> consumer.reconcileScope(SCOPE_ID)).isInstanceOf(IOException.class);
            assertThat(consumer.trackedConsumers(SCOPE_ID)).hasSize(1);

            // The webhook pod finished booting and created the stream.
            consumer.failingStreams.clear();

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(consumer.trackedConsumers(SCOPE_ID))
                            .as("the re-armed retry must bind the stream that failed the first pass")
                            .hasSize(2));
            assertThat(consumer.trackedConsumers(SCOPE_ID))
                    .extracting(ScopeConsumer::streamName)
                    .containsExactlyInAnyOrder(SCM_STREAM, OUTLINE_STREAM);
        }

        @Test
        void shouldKeepConsumerTrackedWhenItFailsToStopForDroppedStream() throws IOException {
            FakeFleet consumer = fleetFailingOn();
            consumer.stopFailingStreams.add(OUTLINE_STREAM);
            consumer.reconcileScope(SCOPE_ID);
            ScopeConsumer outline = consumer.startedOn(OUTLINE_STREAM);
            consumer.subscribedStreams.remove(OUTLINE_STREAM);

            assertThatThrownBy(() -> consumer.reconcileScope(SCOPE_ID)).hasMessage("injected stop failure");
            assertThat(consumer.trackedConsumers(SCOPE_ID)).contains(outline);
            assertThat(outline.isRunning()).isTrue();

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(consumer.trackedConsumers(SCOPE_ID))
                            .extracting(ScopeConsumer::streamName)
                            .containsExactly(SCM_STREAM));
            assertThat(outline.isRunning()).isFalse();
        }

        @Test
        void shouldKeepConsumerTrackedWhenItFailsToStopDuringDeactivationThatRacedItsSetup() throws Exception {
            FakeFleet consumer = fleetFailingOn();
            consumer.stopFailingStreams.add(OUTLINE_STREAM);
            CountDownLatch creating = new CountDownLatch(1);
            CountDownLatch releaseCreation = new CountDownLatch(1);
            consumer.beforeNextCreation.set(() -> {
                creating.countDown();
                try {
                    releaseCreation.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            consumer.startConsumingScope(SCOPE_ID);
            assertThat(creating.await(10, TimeUnit.SECONDS)).isTrue();

            FutureTask<@Nullable Void> deactivation =
                    new FutureTask<>(() -> consumer.stopConsumingScope(SCOPE_ID), null);
            Thread stopper = new Thread(deactivation);
            stopper.start();
            // The only timed wait on the way is the one for the setup in flight, entered with the scope fenced.
            await().atMost(Duration.ofSeconds(10)).until(() -> stopper.getState() == Thread.State.TIMED_WAITING);
            releaseCreation.countDown();

            assertThatThrownBy(() -> deactivation.get(10, TimeUnit.SECONDS))
                    .hasRootCauseMessage("injected stop failure");
            ScopeConsumer outline = consumer.startedOn(OUTLINE_STREAM);
            assertThat(consumer.trackedConsumers(SCOPE_ID)).contains(outline);
            assertThat(outline.isRunning()).isTrue();

            consumer.stopConsumingScope(SCOPE_ID);
            assertThat(consumer.started).hasSize(2).noneMatch(ScopeConsumer::isRunning);
            assertThat(consumer.trackedConsumers(SCOPE_ID)).isEmpty();
        }

        /**
         * The fleet with its two broker-touching seams stubbed out: consumer creation (which streams exist)
         * and connection establishment. Everything under test — the reconcile bookkeeping, the commit of
         * partial successes, the retry re-arm — is the real code.
         */
        private static class FakeFleet extends IntegrationNatsConsumer {

            private final Set<String> failingStreams;
            private final Set<String> subscribedStreams;

            /** Streams whose consumer fails its first stop, as one whose dispatch thread outlives the stop. */
            private final Set<String> stopFailingStreams = new ConcurrentSkipListSet<>();

            private final AtomicReference<@Nullable Runnable> beforeNextCreation = new AtomicReference<>();
            private final List<ScopeConsumer> started = new CopyOnWriteArrayList<>();

            FakeFleet(Set<String> failingStreams) {
                this(failingStreams, new CopyOnWriteArraySet<>(List.of(SCM_STREAM, OUTLINE_STREAM)));
            }

            private FakeFleet(Set<String> failingStreams, Set<String> subscribedStreams) {
                super(
                        new NatsConnectionProperties(
                                true,
                                "nats://localhost:4222",
                                "heph",
                                new NatsConnectionProperties.Consumer(Duration.ofSeconds(60))),
                        NatsConsumerPropertiesFixture.withFastPoisonBackoff(),
                        scopeId -> Optional.of(new NatsSubscriptionInfo(
                                scopeId,
                                subscribedStreams.stream()
                                        .map(stream -> new StreamSubscription(stream, Set.of(stream + ".acme.>")))
                                        .toList())),
                        mock(IntegrationMessageDispatcher.class),
                        mock(IntegrationPoisonHandler.class),
                        new IntegrationConsumerStats(),
                        mock(ConnectionActivityRecorder.class),
                        List.of());
                this.failingStreams = new ConcurrentSkipListSet<>(failingStreams);
                this.subscribedStreams = subscribedStreams;
            }

            ScopeConsumer startedOn(String streamName) {
                return started.stream()
                        .filter(consumer -> consumer.streamName().equals(streamName))
                        .findFirst()
                        .orElseThrow();
            }

            @Override
            void ensureNatsConnectionEstablished() {
                // no broker in a unit test
            }

            @Override
            ScopeConsumer createScopeConsumer(Long scopeId, StreamSubscription subscription) throws IOException {
                Runnable hook = beforeNextCreation.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                if (failingStreams.contains(subscription.streamName())) {
                    throw new IOException("stream not found: " + subscription.streamName());
                }
                ScopeConsumer scopeConsumer = new ScopeConsumer(
                        scopeId,
                        "heph-scope-" + scopeId + "-" + subscription.streamName(),
                        subscription.streamName(),
                        mock(ConsumerContext.class),
                        mock(StreamContext.class),
                        subscription.subjects().toArray(String[]::new),
                        msg -> {});
                if (stopFailingStreams.contains(subscription.streamName())) {
                    scopeConsumer = spy(scopeConsumer);
                    doThrow(new IllegalStateException("injected stop failure"))
                            .doCallRealMethod()
                            .when(scopeConsumer)
                            .stop();
                }
                try {
                    scopeConsumer.start();
                } catch (Exception e) {
                    throw new IOException(e);
                }
                started.add(scopeConsumer);
                return scopeConsumer;
            }
        }
    }
}
