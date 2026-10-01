package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.NatsSubscriptionInfo;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.StreamSubscription;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.Nats;
import io.nats.client.StreamContext;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The consumer fleet against a real JetStream server when a repository is added to a scope while the
 * scope's start or establishment is still in flight: the change must reach the durable's filter, and
 * establishment must not report readiness before it has. When the first of a scope's streams fails to update,
 * every stream keeps its consumer.
 */
@Tag("integration")
class ScopeConsumerReconcileIntegrationTest {

    private static final String STREAM = "gitlab";
    private static final long SCOPE_ID = 9L;
    private static final String REPOSITORY = "hephaestustest/introcourse/demo-heph-e2e";
    private static final String DISCOVERED_REPOSITORY = "hephaestustest/introcourse/discovered";
    private static final String DISCOVERED_ISSUE_SUBJECT = "gitlab.hephaestustest~introcourse.discovered.issue";
    private static final String OUTLINE_STREAM = "outline";
    private static final String OUTLINE_SUBSCRIPTION = "5b0f3a1e-4c52-4d8e-9a57-2f7c1b9e0d31";
    private static final String ADDED_OUTLINE_SUBSCRIPTION = "9e2d7c40-1f6b-4a3e-8c95-6d0a4b7e2f18";

    private final Set<String> monitoredRepositories = new CopyOnWriteArraySet<>(Set.of(REPOSITORY));
    private final Set<String> outlineSubscriptions = new CopyOnWriteArraySet<>();
    private final AtomicReference<@Nullable Runnable> afterNextSubscriptionRead = new AtomicReference<>();
    private final BlockingQueue<String> handledSubjects = new LinkedBlockingQueue<>();
    private final CountDownLatch startReadSubscriptions = new CountDownLatch(1);
    private final CountDownLatch releaseStart = new CountDownLatch(1);

    private Connection natsConnection;
    private JetStreamManagement jsm;
    private IntegrationNatsConsumer fleet;

    @BeforeEach
    void setUp() throws Exception {
        natsConnection = Nats.connect(NatsTestContainer.getServerUrl());
        jsm = natsConnection.jetStreamManagement();
        jsm.addStream(StreamConfiguration.builder()
                .name(STREAM)
                .subjects(STREAM + ".>")
                .storageType(StorageType.Memory)
                .build());
        jsm.addStream(StreamConfiguration.builder()
                .name(OUTLINE_STREAM)
                .subjects(OUTLINE_STREAM + ".>")
                .storageType(StorageType.Memory)
                .build());
        afterNextSubscriptionRead.set(() -> {
            startReadSubscriptions.countDown();
            awaitUninterruptibly(releaseStart);
        });
        fleet = connectedFleet();
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseStart.countDown();
        fleet.shutdown();
        jsm.deleteStream(STREAM);
        jsm.deleteStream(OUTLINE_STREAM);
        natsConnection.close();
    }

    @Test
    void shouldConsumeRepositoryAddedWhileScopeStartIsInFlight() throws Exception {
        fleet.startConsumingScope(SCOPE_ID);
        assertThat(startReadSubscriptions.await(10, SECONDS)).isTrue();
        monitoredRepositories.add(DISCOVERED_REPOSITORY);
        fleet.updateScopeConsumer(SCOPE_ID);
        releaseStart.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() -> filterSubjects().equals(expectedFilter()));
        natsConnection.jetStream().publish(DISCOVERED_ISSUE_SUBJECT, new byte[0]);
        assertThat(handledSubjects.poll(10, SECONDS)).isEqualTo(DISCOVERED_ISSUE_SUBJECT);
    }

    @Test
    void shouldEstablishRepositoryAddedWhileScopeStartIsInFlight() throws Exception {
        fleet.startConsumingScope(SCOPE_ID);
        assertThat(startReadSubscriptions.await(10, SECONDS)).isTrue();
        monitoredRepositories.add(DISCOVERED_REPOSITORY);
        CompletableFuture<Void> established = CompletableFuture.runAsync(() -> {
            try {
                fleet.establishScopeConsumer(SCOPE_ID, STREAM);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        releaseStart.countDown();

        established.get(10, SECONDS);
        assertThat(filterSubjects()).isEqualTo(expectedFilter());
    }

    @Test
    void shouldEstablishOnlyOnceRepositoryAddedDuringEstablishmentIsInstalled() throws Exception {
        CompletableFuture<Void> established = CompletableFuture.runAsync(() -> {
            try {
                fleet.establishScopeConsumer(SCOPE_ID, STREAM);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(startReadSubscriptions.await(10, SECONDS)).isTrue();
        CountDownLatch secondPassReadSubscriptions = new CountDownLatch(1);
        CountDownLatch releaseSecondPass = new CountDownLatch(1);
        afterNextSubscriptionRead.set(() -> {
            secondPassReadSubscriptions.countDown();
            awaitUninterruptibly(releaseSecondPass);
        });
        monitoredRepositories.add(DISCOVERED_REPOSITORY);
        fleet.updateScopeConsumer(SCOPE_ID);
        releaseStart.countDown();

        assertThat(secondPassReadSubscriptions.await(10, SECONDS)).isTrue();
        assertThat(established).failsWithin(Duration.ofMillis(500));
        releaseSecondPass.countDown();
        established.get(10, SECONDS);

        assertThat(filterSubjects()).isEqualTo(expectedFilter());
        natsConnection.jetStream().publish(DISCOVERED_ISSUE_SUBJECT, new byte[0]);
        assertThat(handledSubjects.poll(10, SECONDS)).isEqualTo(DISCOVERED_ISSUE_SUBJECT);
    }

    @Test
    void shouldRefuseToEstablishWhenScopeSubscribesToNothingOnTheStream() {
        afterNextSubscriptionRead.set(null);
        monitoredRepositories.clear();

        assertThatThrownBy(() -> fleet.establishScopeConsumer(SCOPE_ID, STREAM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(STREAM);
    }

    @Test
    void shouldReportReadyOnlyOnceSubscriptionLostToFailedRecycleIsReattached() throws Exception {
        afterNextSubscriptionRead.set(null);
        IntegrationNatsConsumer failingFleet =
                new IntegrationNatsConsumer(
                        fleetProperties(),
                        NatsConsumerPropertiesFixture.defaults(),
                        this::subscriptionInfo,
                        dispatcher(),
                        mock(IntegrationPoisonHandler.class),
                        new IntegrationConsumerStats(),
                        mock(ConnectionActivityRecorder.class),
                        List.of()) {
                    /** A real durable whose second subscribe (the subject recycle) fails once. */
                    @Override
                    ScopeConsumer createScopeConsumer(Long scopeId, StreamSubscription subscription)
                            throws IOException {
                        try {
                            StreamContext stream = natsConnection.getStreamContext(STREAM);
                            String[] subjects = subscription.subjects().toArray(String[]::new);
                            ConsumerContext context = spy(stream.createOrUpdateConsumer(ConsumerConfiguration.builder()
                                    .durable("reattach-" + scopeId)
                                    .filterSubjects(subjects)
                                    .deliverPolicy(DeliverPolicy.New)
                                    .build()));
                            doCallRealMethod()
                                    .doThrow(new IOException("injected resubscribe failure"))
                                    .doCallRealMethod()
                                    .when(context)
                                    .consume(any(MessageHandler.class));
                            ScopeConsumer consumer = new ScopeConsumer(
                                    scopeId,
                                    "reattach-" + scopeId,
                                    STREAM,
                                    context,
                                    stream,
                                    subjects,
                                    msg -> handleMessage(scopeId, msg));
                            consumer.start();
                            return consumer;
                        } catch (JetStreamApiException e) {
                            throw new IOException(e);
                        }
                    }
                };
        failingFleet.onApplicationReady();
        try {
            failingFleet.establishScopeConsumer(SCOPE_ID, STREAM);
            monitoredRepositories.add(DISCOVERED_REPOSITORY);

            assertThatThrownBy(() -> failingFleet.establishScopeConsumer(SCOPE_ID, STREAM))
                    .hasRootCauseMessage("injected resubscribe failure");
            failingFleet.establishScopeConsumer(SCOPE_ID, STREAM);

            assertThat(filterSubjects()).isEqualTo(expectedFilter());
            natsConnection.jetStream().publish(DISCOVERED_ISSUE_SUBJECT, new byte[0]);
            assertThat(handledSubjects.poll(10, SECONDS)).isEqualTo(DISCOVERED_ISSUE_SUBJECT);
        } finally {
            failingFleet.shutdown();
        }
    }

    @Test
    void shouldKeepConsumingEveryStreamWhenUpdatingTheFirstFails() throws Exception {
        afterNextSubscriptionRead.set(null);
        outlineSubscriptions.add(OUTLINE_SUBSCRIPTION);
        AtomicBoolean failNextGitLabUpdate = new AtomicBoolean();
        IntegrationConsumerStats stats = new IntegrationConsumerStats();
        IntegrationNatsConsumer failingFleet =
                new IntegrationNatsConsumer(
                        fleetProperties(),
                        NatsConsumerPropertiesFixture.defaults(),
                        this::subscriptionInfo,
                        dispatcher(),
                        mock(IntegrationPoisonHandler.class),
                        stats,
                        mock(ConnectionActivityRecorder.class),
                        List.of()) {
                    /** Real durables on both streams; the GitLab filter update fails once when armed. */
                    @Override
                    ScopeConsumer createScopeConsumer(Long scopeId, StreamSubscription subscription)
                            throws IOException {
                        try {
                            String streamName = subscription.streamName();
                            String durable = "partial-" + scopeId + "-" + streamName;
                            String[] subjects = subscription.subjects().toArray(String[]::new);
                            StreamContext stream = spy(natsConnection.getStreamContext(streamName));
                            ConsumerContext context = stream.createOrUpdateConsumer(ConsumerConfiguration.builder()
                                    .durable(durable)
                                    .filterSubjects(subjects)
                                    .deliverPolicy(DeliverPolicy.New)
                                    .build());
                            doAnswer(update -> {
                                        if (streamName.equals(STREAM) && failNextGitLabUpdate.getAndSet(false)) {
                                            throw new IOException("injected update failure");
                                        }
                                        return update.callRealMethod();
                                    })
                                    .when(stream)
                                    .createOrUpdateConsumer(any(ConsumerConfiguration.class));
                            ScopeConsumer consumer = new ScopeConsumer(
                                    scopeId,
                                    durable,
                                    streamName,
                                    context,
                                    stream,
                                    subjects,
                                    msg -> handleMessage(scopeId, msg));
                            consumer.start();
                            return consumer;
                        } catch (JetStreamApiException e) {
                            throw new IOException(e);
                        }
                    }
                };
        failingFleet.onApplicationReady();
        try {
            failingFleet.establishScopeConsumer(SCOPE_ID, STREAM);
            List<ScopeConsumer> started = List.copyOf(failingFleet.trackedConsumers(SCOPE_ID));
            assertThat(started).extracting(ScopeConsumer::streamName).containsExactly(STREAM, OUTLINE_STREAM);

            monitoredRepositories.add(DISCOVERED_REPOSITORY);
            outlineSubscriptions.add(ADDED_OUTLINE_SUBSCRIPTION);
            failNextGitLabUpdate.set(true);
            assertThatThrownBy(() -> failingFleet.establishScopeConsumer(SCOPE_ID, STREAM))
                    .hasRootCauseMessage("injected update failure");

            assertThat(failingFleet.trackedConsumers(SCOPE_ID)).containsExactlyElementsOf(started);
            assertThat(started).allMatch(ScopeConsumer::isAttached);
            assertThat(stats.activeScopeConsumerCount()).isEqualTo(2);

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> filterSubjects(STREAM).equals(expectedFilter())
                            && filterSubjects(OUTLINE_STREAM).equals(expectedOutlineFilter()));
            assertThat(failingFleet.trackedConsumers(SCOPE_ID)).containsExactlyElementsOf(started);
            assertThat(stats.activeScopeConsumerCount()).isEqualTo(2);

            failingFleet.stopConsumingScope(SCOPE_ID);
            assertThat(started).noneMatch(ScopeConsumer::isRunning);
            assertThat(failingFleet.trackedConsumers(SCOPE_ID)).isEmpty();
            assertThat(stats.activeScopeConsumerCount()).isZero();
        } finally {
            failingFleet.shutdown();
        }
    }

    private IntegrationNatsConsumer connectedFleet() {
        IntegrationNatsConsumer consumer = new IntegrationNatsConsumer(
                fleetProperties(),
                NatsConsumerPropertiesFixture.defaults(),
                this::subscriptionInfo,
                dispatcher(),
                mock(IntegrationPoisonHandler.class),
                new IntegrationConsumerStats(),
                mock(ConnectionActivityRecorder.class),
                List.of());
        consumer.onApplicationReady();
        return consumer;
    }

    private static NatsConnectionProperties fleetProperties() {
        return new NatsConnectionProperties(
                true,
                NatsTestContainer.getServerUrl(),
                "reconcile",
                new NatsConnectionProperties.Consumer(Duration.ofSeconds(10)));
    }

    private IntegrationMessageDispatcher dispatcher() {
        IntegrationMessageHandler handler = new IntegrationMessageHandler() {
            @Override
            public EventTypeKey key() {
                return new EventTypeKey(IntegrationKind.GITLAB, "issue");
            }

            @Override
            public void onMessage(Message msg) {
                handledSubjects.add(msg.getSubject());
            }
        };
        IntegrationMessageDispatcher dispatcher = mock(IntegrationMessageDispatcher.class);
        when(dispatcher.dispatch(anyString())).thenReturn(Optional.of(handler));
        return dispatcher;
    }

    private Optional<NatsSubscriptionInfo> subscriptionInfo(Long scopeId) {
        Set<String> subjects = monitoredRepositories.stream()
                .map(repository -> ConsumerSubjectMath.repositoryFilter(STREAM, repository))
                .collect(Collectors.toSet());
        Runnable hook = afterNextSubscriptionRead.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        Set<String> outlineSubjects = outlineSubscriptions.stream()
                .map(subscription -> ConsumerSubjectMath.subscriptionFilter(OUTLINE_STREAM, subscription))
                .collect(Collectors.toSet());
        return Optional.of(new NatsSubscriptionInfo(
                scopeId,
                List.of(
                        new StreamSubscription(STREAM, subjects),
                        new StreamSubscription(OUTLINE_STREAM, outlineSubjects))));
    }

    private static Set<String> expectedFilter() {
        return Set.of(
                ConsumerSubjectMath.repositoryFilter(STREAM, REPOSITORY),
                ConsumerSubjectMath.repositoryFilter(STREAM, DISCOVERED_REPOSITORY));
    }

    private static Set<String> expectedOutlineFilter() {
        return Set.of(
                ConsumerSubjectMath.subscriptionFilter(OUTLINE_STREAM, OUTLINE_SUBSCRIPTION),
                ConsumerSubjectMath.subscriptionFilter(OUTLINE_STREAM, ADDED_OUTLINE_SUBSCRIPTION));
    }

    private Set<String> filterSubjects() throws Exception {
        return filterSubjects(STREAM);
    }

    /** The filter of the scope's only durable on the stream, which is deleted after every test. */
    private Set<String> filterSubjects(String stream) throws Exception {
        return jsm.getConsumers(stream).stream()
                .flatMap(
                        info -> Objects.requireNonNullElse(
                                info.getConsumerConfiguration().getFilterSubjects(), List.<String>of())
                                .stream())
                .collect(Collectors.toSet());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
