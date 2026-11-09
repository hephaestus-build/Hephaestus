package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationCoreConnectionPersonDataCatalog;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.NatsSubscriptionInfo;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.StreamSubscription;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.Headers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class ConsumerDeadLetterIntegrationTest extends AbstractWorkspaceIntegrationTest {
    // This broker is private: real provider subjects must not overlap other integration suites.
    private static final GenericContainer<?> BROKER = new GenericContainer<>(NatsTestContainer.IMAGE)
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
    private Connection broker;
    private String stream;
    private @Nullable Object originalConnection;
    private @Nullable Object originalSubscriptions;
    private @Nullable Object originalDispatcher;
    private @Nullable Object originalPoisonHandler;

    @Autowired
    private IntegrationNatsConsumer consumer;

    @Autowired
    private WorkspaceRepository deadLetterWorkspaces;

    @BeforeAll
    static void startBroker() {
        BROKER.start();
    }

    @AfterAll
    static void stopBroker() {
        BROKER.stop();
    }

    @AfterEach
    void closeBrokerConnection() throws Exception {
        ReflectionTestUtils.setField(consumer, "natsConnection", originalConnection);
        ReflectionTestUtils.setField(consumer, "subscriptionProvider", originalSubscriptions);
        ReflectionTestUtils.setField(consumer, "dispatcher", originalDispatcher);
        ReflectionTestUtils.setField(consumer, "poisonHandler", originalPoisonHandler);
        broker.jetStreamManagement().deleteStream(stream);
        broker.close();
    }

    @Autowired
    private ConsumerDeadLetterService service;

    @Autowired
    private IntegrationCoreConnectionPersonDataCatalog privacy;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AuthEventRepository authEvents;

    @Autowired
    private WebTestClient client;

    @Autowired
    private ClusterLock locks;

    private Workspace workspace;
    private Workspace other;

    @BeforeEach
    void setUp() throws Exception {
        broker = Nats.connect("nats://" + BROKER.getHost() + ":" + BROKER.getMappedPort(4222));
        stream = "github";
        broker.jetStreamManagement()
                .addStream(StreamConfiguration.builder()
                        .name(stream)
                        .subjects("github.>")
                        .build());
        originalConnection = ReflectionTestUtils.getField(consumer, "natsConnection");
        originalSubscriptions = ReflectionTestUtils.getField(consumer, "subscriptionProvider");
        originalDispatcher = ReflectionTestUtils.getField(consumer, "dispatcher");
        originalPoisonHandler = ReflectionTestUtils.getField(consumer, "poisonHandler");
        ReflectionTestUtils.setField(consumer, "natsConnection", broker);
        var owner = persistUser("admin");
        workspace = createWorkspace("deadletters-a", "Events A", "events-a", AccountType.ORG, owner);
        other = createWorkspace("deadletters-b", "Events B", "events-b", AccountType.ORG, owner);
        NatsSubscriptionProvider subscriptions = scope -> Optional.of(
                new NatsSubscriptionInfo(scope, List.of(new StreamSubscription(stream, Set.of("github.acme.repo.>")))));
        ReflectionTestUtils.setField(consumer, "subscriptionProvider", subscriptions);
    }

    @Test
    @WithAdminUser
    void shouldDeduplicateCaptureAndKeepWorkspaceRecordsOutOfOtherScopes() throws Exception {
        Message message = delivery("github.acme.repo.issues", "not-json", "scope-a");
        service.capture(
                workspace.getId(),
                message,
                new IllegalStateException("wrapper", new PayloadParsingException("private-canary")));
        service.capture(workspace.getId(), message, new PayloadParsingException("private-canary"));
        var rows = service.listWorkspace(workspace.getId(), null, 100).content();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().errorClass()).isEqualTo(PayloadParsingException.class.getName());
        assertThat(service.listWorkspace(other.getId(), null, 100).content()).isEmpty();
        client.get()
                .uri("/workspaces/deadletters-a/integrations/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(rows.getFirst().id())
                .jsonPath("$.content[0].payload")
                .doesNotExist()
                .jsonPath("$.content[0].subject")
                .doesNotExist();
        client.post()
                .uri(
                        "/workspaces/deadletters-b/integrations/dead-letters/{id}/replays",
                        rows.getFirst().id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.post()
                .uri(
                        "/admin/integration/dead-letters/{id}/replays",
                        rows.getFirst().id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldReadRecordsForwardOnceWithACursor() throws Exception {
        for (String durable : List.of("page-a", "page-b", "page-c")) {
            service.capture(
                    workspace.getId(),
                    delivery("github.acme.repo.issues", "not-json", durable),
                    new PayloadParsingException("private-canary"));
        }

        var first = service.listWorkspace(workspace.getId(), null, 2);
        assertThat(first.content()).hasSize(2);
        var second = service.listWorkspace(workspace.getId(), first.nextCursor(), 2);
        assertThat(second.content()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.content().getFirst().id())
                .isGreaterThan(first.content().getLast().id());

        assertThatThrownBy(() -> service.listWorkspace(workspace.getId(), "not-a-cursor", 2))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @WithAdminUser
    void shouldKeepFailedReplayPending() throws Exception {
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "not-json", "scope-b"),
                new PayloadParsingException("invalid JSON"));
        var row = service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(service.listWorkspace(workspace.getId(), null, 100)
                        .content()
                        .getFirst()
                        .replayedAt())
                .isNull();
    }

    @Test
    @WithAdminUser
    void shouldAnswerUnavailableWhileAnotherServerReplaysTheSameConsumer() throws Exception {
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "{\"action\":\"ignored\"}", "scope-lock"),
                new PayloadParsingException("older parser"));
        var row = service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        // Another server's replay of the same consumer holds its lock.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = Thread.ofVirtual()
                .start(() -> locks.run(
                        "consumer-replay:" + consumer.fencedConsumerName(workspace.getId(), stream),
                        Duration.ZERO,
                        () -> {
                            held.countDown();
                            try {
                                release.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }));
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            client.post()
                    .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody(Void.class);
        } finally {
            release.countDown();
            other.join(Duration.ofSeconds(10));
        }
        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .filteredOn(candidate -> candidate.id().equals(row.id()))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.replayedAt()).isNull());
    }

    @Test
    @WithAdminUser
    void shouldReplayTheStoredEnvelopeOnlyOnceAfterHandlerSucceeds() throws Exception {
        // An ignored action is a valid event: dispatch succeeds without importing any provider data.
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "{\"action\":\"ignored\"}", "scope-c"),
                new PayloadParsingException("older parser"));
        var row = service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        for (int call = 0; call < 2; call++) {
            client.post()
                    .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.replayedAt")
                    .exists();
        }
        assertThat(service.listWorkspace(workspace.getId(), null, 100)
                        .content()
                        .getFirst()
                        .replayedAt())
                .isNotNull();
    }

    @Test
    @WithAdminUser
    void shouldKeepUnassignedInstallationRowsSeparateFromWorkspaceRows() throws Exception {
        service.capture(
                null,
                delivery("github.installation.123.installation", "not-json", "installation"),
                new PayloadParsingException("invalid JSON"));
        var row = service.listInstallation(null, 100).content().getFirst();
        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .isEmpty();
        client.get()
                .uri("/admin/integration/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(row.id());
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithUser
    void shouldRejectMembersWhoAreNotWorkspaceAdministrators() {
        ensureMembership(workspace, persistUser("testuser"), WorkspaceRole.MEMBER);
        client.get()
                .uri("/admin/integration/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        client.get()
                .uri("/workspaces/deadletters-a/integrations/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldCommitOneRowBeforeTermAndContinueToTheNextSequence() throws Exception {
        Message first = delivery("github.acme.repo.issues", "not-json", "parse");
        var properties = NatsConsumerPropertiesFixture.defaults();
        var poison = new IntegrationPoisonHandler(properties, new SimpleMeterRegistry(), service);
        var context = broker.getStreamContext(stream).getConsumerContext("parse");
        broker.jetStream().publish("github.acme.repo.issues", "next".getBytes(StandardCharsets.UTF_8));
        // Simulate a crash after commit, before TERM reaches the broker.
        Message lostTerm = spy(first);
        doThrow(new IllegalStateException("connection closed")).when(lostTerm).term();
        poison.handleFailure(workspace.getId(), lostTerm, new PayloadParsingException("bad"));
        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .hasSize(1);
        Message redelivery = context.next(Duration.ofSeconds(5));
        assertThat(redelivery).isNotNull();
        if (redelivery == null) throw new AssertionError("Missing redelivery");
        assertThat(redelivery.metaData().streamSequence())
                .isEqualTo(first.metaData().streamSequence());
        poison.handleFailure(workspace.getId(), redelivery, new PayloadParsingException("bad"));
        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .hasSize(1);
        Message next = context.next(Duration.ofSeconds(5));
        assertThat(next).isNotNull();
        if (next == null) throw new AssertionError("Next sequence was blocked");
        assertThat(new String(next.getData(), StandardCharsets.UTF_8)).isEqualTo("next");
        next.ack();
    }

    @Test
    void shouldRetryWithoutTermWhenCaptureCannotCommit() throws Exception {
        Message first = spy(delivery("github.acme.repo.issues", "bad", "capture"));
        var poison = new IntegrationPoisonHandler(
                NatsConsumerPropertiesFixture.defaults(), new SimpleMeterRegistry(), service);
        poison.handleFailure(Long.MAX_VALUE, first, new PayloadParsingException("bad"));
        verify(first, never()).term();
        verify(first).nakWithDelay(any(Duration.class));
        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .isEmpty();
        Message redelivery =
                broker.getStreamContext(stream).getConsumerContext("capture").next(Duration.ofSeconds(5));
        assertThat(redelivery).isNotNull();
        if (redelivery == null) throw new AssertionError("Capture failure lost its event");
        assertThat(redelivery.metaData().streamSequence())
                .isEqualTo(first.metaData().streamSequence());
        redelivery.ack();
    }

    @Test
    @WithAdminUser
    void shouldReturnGoneWhenTheOriginalBrokerEventHasExpired() throws Exception {
        Message message = delivery("github.acme.repo.issues", "{}", "expired");
        service.capture(workspace.getId(), message, new PayloadParsingException("old"));
        var row = service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        broker.jetStreamManagement().deleteMessage(stream, message.metaData().streamSequence());
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(410)
                .expectBody(Void.class);
        assertThat(service.listWorkspace(workspace.getId(), null, 100)
                        .content()
                        .getFirst()
                        .replayedAt())
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @WithAdminUser
    void shouldRollBackPartialHandlerWritesAndKeepReplayPending(boolean illegalState) throws Exception {
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "{}", "rollback"),
                new PayloadParsingException("old"));
        var row = service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        IntegrationMessageDispatcher original =
                (IntegrationMessageDispatcher) ReflectionTestUtils.getField(consumer, "dispatcher");
        var dispatcher = mock(IntegrationMessageDispatcher.class);
        var handler = mock(IntegrationMessageHandler.class);
        when(dispatcher.dispatch("github.acme.repo.issues")).thenReturn(Optional.of(handler));
        doAnswer(call -> {
                    transactions.executeWithoutResult(status -> {
                        if (!DeliveryInbox.claim("test.rollback")) return;
                        workspace.setDisplayName("partial replay");
                        deadLetterWorkspaces.saveAndFlush(workspace);
                        if (illegalState) throw new IllegalStateException("failure after a database write");
                        throw new IllegalArgumentException("failure after a database write");
                    });
                    return null;
                })
                .when(handler)
                .onMessage(any());
        ReflectionTestUtils.setField(consumer, "dispatcher", dispatcher);
        try {
            client.post()
                    .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody(Void.class);
            assertThat(deadLetterWorkspaces
                            .findById(workspace.getId())
                            .orElseThrow()
                            .getDisplayName())
                    .isEqualTo("Events A");
            assertThat(service.listWorkspace(workspace.getId(), null, 100)
                            .content()
                            .getFirst()
                            .replayedAt())
                    .isNull();
        } finally {
            ReflectionTestUtils.setField(consumer, "dispatcher", original);
        }
    }

    @Test
    @WithAdminUser
    void shouldRejectReplayAfterTheWorkspaceUnsubscribes() throws Exception {
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "{\"action\":\"ignored\"}", "unsubscribed"),
                new PayloadParsingException("old"));
        var row = service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        ReflectionTestUtils.setField(
                consumer, "subscriptionProvider", (NatsSubscriptionProvider) scope -> Optional.empty());
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(service.listWorkspace(workspace.getId(), null, 100)
                        .content()
                        .getFirst()
                        .replayedAt())
                .isNull();
    }

    @Test
    @WithAdminUser
    void shouldExportAndEraseTheReplayActorsAttribution() throws Exception {
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "{\"action\":\"ignored\"}", "privacy"),
                new PayloadParsingException("old"));
        var row = service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        var replayed =
                service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        Long actor = Objects.requireNonNull(replayed.replayedBy());
        var scope = new PersonScope(actor, List.of(), List.of());
        var store = privacy.contributors().stream()
                .filter(candidate -> candidate.store().equals("consumer_dead_letter"))
                .findFirst()
                .orElseThrow();
        var selected = store.select(scope);
        var exported = store.export(selected, scope);
        assertThat(exported).hasSize(1);
        assertThat(exported.getFirst().path("id").asLong()).isEqualTo(row.id());
        assertThat(exported.getFirst().has("subject")).isFalse();
        assertThat(exported.getFirst().has("payload")).isFalse();
        transactions.executeWithoutResult(
                status -> assertThat(store.erase(selected, scope)).isEqualTo(1));
        var erased =
                service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        assertThat(erased.replayedBy()).isNull();
        assertThat(erased.replayedAt()).isEqualTo(replayed.replayedAt());
    }

    @Test
    @WithAdminUser
    void shouldAuditOneCompletedInstallationReplay() throws Exception {
        service.capture(
                null,
                delivery("github.?.?.installation", "{\"action\":\"ignored\"}", "installation-replay"),
                new PayloadParsingException("old"));
        var row = service.listInstallation(null, 100).content().getFirst();
        for (int attempt = 0; attempt < 2; attempt++) {
            client.post()
                    .uri("/admin/integration/dead-letters/{id}/replays", row.id())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
        }
        var replayed = service.listInstallation(null, 100).content().getFirst();
        assertThat(replayed.replayedAt()).isNotNull();
        assertThat(authEvents.findAll().stream()
                        .filter(event -> event.getEventType() == AuthEvent.EventType.INSTALLATION_EVENT_REPLAYED
                                && ("{\"deadLetterId\":" + row.id() + "}").equals(event.getDetails()))
                        .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getResult()).isEqualTo(AuthEvent.Result.SUCCESS);
                    assertThat(event.getAccountId()).isEqualTo(replayed.replayedBy());
                });
    }

    @Test
    @WithAdminUser
    void shouldSkipCommittedDeliveryAfterLostAcknowledgementAndAdministratorReplay() throws Exception {
        var handler = useHandler();
        doAnswer(call -> {
                    appendInStep("test.effect", " committed");
                    return null;
                })
                .when(handler)
                .onMessage(any());
        Message first = delivery("github.acme.repo.issues", "{}", "lost-ack");
        Message lostAck = spy(first);
        doThrow(new IllegalStateException("connection closed")).when(lostAck).ack();
        consumer.handleMessage(workspace.getId(), lostAck);

        Message redelivery =
                broker.getStreamContext(stream).getConsumerContext("lost-ack").next(Duration.ofSeconds(5));
        if (redelivery == null) throw new AssertionError("Missing redelivery after lost acknowledgement");
        assertThat(redelivery.metaData().streamSequence())
                .isEqualTo(first.metaData().streamSequence());
        assertThat(redelivery.metaData().deliveredCount()).isEqualTo(2);
        consumer.handleMessage(workspace.getId(), redelivery);

        service.capture(workspace.getId(), first, new IllegalStateException("settlement failed"));
        var row = service.listWorkspace(workspace.getId(), null, 100).content().getFirst();
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(deadLetterWorkspaces
                        .findById(workspace.getId())
                        .orElseThrow()
                        .getDisplayName())
                .isEqualTo("Events A committed");
        verify(handler, times(1)).onMessage(any());
        assertThat(service.listWorkspace(workspace.getId(), null, 100)
                        .content()
                        .getFirst()
                        .replayedAt())
                .isNotNull();
    }

    @Test
    void shouldExhaustPoisonBudgetAfterAPartialCommitAndContinueToTheNextEvent() throws Exception {
        var handler = useHandler();
        ReflectionTestUtils.setField(
                consumer,
                "poisonHandler",
                new IntegrationPoisonHandler(
                        NatsConsumerPropertiesFixture.withFastPoisonBackoff(), new SimpleMeterRegistry(), service));
        doAnswer(call -> {
                    Message message = call.getArgument(0);
                    if ("next".equals(new String(message.getData(), StandardCharsets.UTF_8))) return null;
                    appendInStep("test.first", " first");
                    transactions.executeWithoutResult(status -> {
                        if (!DeliveryInbox.claim("test.second")) return;
                        var current =
                                deadLetterWorkspaces.findById(workspace.getId()).orElseThrow();
                        current.setDisplayName(current.getDisplayName() + " rolled back");
                        deadLetterWorkspaces.saveAndFlush(current);
                        throw new IllegalStateException("unfinished second step");
                    });
                    return null;
                })
                .when(handler)
                .onMessage(any());
        Message first = delivery("github.acme.repo.issues", "{}", "partial");
        broker.jetStream().publish("github.acme.repo.issues", "next".getBytes(StandardCharsets.UTF_8));
        var context = broker.getStreamContext(stream).getConsumerContext("partial");
        Message current = first;
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(current.metaData().streamSequence())
                    .isEqualTo(first.metaData().streamSequence());
            assertThat(current.metaData().deliveredCount()).isEqualTo(attempt);
            consumer.handleMessage(workspace.getId(), current);
            if (attempt < 10) {
                Message retry = context.next(Duration.ofSeconds(5));
                if (retry == null) throw new AssertionError("Partial commit stopped retrying");
                current = retry;
            }
        }
        assertThat(deadLetterWorkspaces
                        .findById(workspace.getId())
                        .orElseThrow()
                        .getDisplayName())
                .isEqualTo("Events A first");
        assertThat(service.listWorkspace(workspace.getId(), null, 100).content())
                .singleElement()
                .satisfies(row ->
                        assertThat(row.sequence()).isEqualTo(first.metaData().streamSequence()));
        Message next = context.next(Duration.ofSeconds(5));
        if (next == null) throw new AssertionError("Poison delivery blocked the next event");
        assertThat(new String(next.getData(), StandardCharsets.UTF_8)).isEqualTo("next");
        consumer.handleMessage(workspace.getId(), next);
    }

    private IntegrationMessageHandler useHandler() {
        var dispatcher = mock(IntegrationMessageDispatcher.class);
        var handler = mock(IntegrationMessageHandler.class);
        when(handler.key()).thenReturn(new EventTypeKey(IntegrationKind.GITHUB, "issues"));
        when(dispatcher.dispatch("github.acme.repo.issues")).thenReturn(Optional.of(handler));
        ReflectionTestUtils.setField(consumer, "dispatcher", dispatcher);
        return handler;
    }

    private void appendInStep(String step, String suffix) {
        transactions.executeWithoutResult(status -> {
            if (!DeliveryInbox.claim(step)) return;
            var current = deadLetterWorkspaces.findById(workspace.getId()).orElseThrow();
            current.setDisplayName(current.getDisplayName() + suffix);
            deadLetterWorkspaces.saveAndFlush(current);
        });
    }

    private Message delivery(String subject, String payload, String durable) throws Exception {
        var context = broker.getStreamContext(stream)
                .createOrUpdateConsumer(IntegrationNatsConsumer.newConsumerConfiguration(
                        new String[] {subject}, NatsConsumerPropertiesFixture.defaults(), durable));
        broker.jetStream()
                .publish(
                        subject,
                        new Headers().put("route-canary", "original"),
                        payload.getBytes(StandardCharsets.UTF_8));
        Message message = context.next(Duration.ofSeconds(5));
        if (message == null) throw new AssertionError("Missing broker delivery");
        return message;
    }
}
