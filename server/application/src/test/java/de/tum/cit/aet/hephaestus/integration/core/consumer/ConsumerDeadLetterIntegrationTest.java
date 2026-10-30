package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationCoreConnectionPersonDataCatalog;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.NatsSubscriptionInfo;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.StreamSubscription;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class ConsumerDeadLetterIntegrationTest extends AbstractWorkspaceIntegrationTest {
    // This broker is private: real provider subjects must not overlap other integration suites.
    private static final GenericContainer<?> BROKER = new GenericContainer<>("nats:2.10.27-alpine")
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
    private Connection broker;
    private String stream;
    private @Nullable Object originalConnection;
    private @Nullable Object originalSubscriptions;

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
        var rows = service.listWorkspace(workspace.getId(), 0);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().errorClass()).isEqualTo(PayloadParsingException.class.getName());
        assertThat(service.listWorkspace(other.getId(), 0)).isEmpty();
        client.get()
                .uri("/workspaces/deadletters-a/integrations/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(rows.getFirst().id())
                .jsonPath("$[0].payload")
                .doesNotExist()
                .jsonPath("$[0].subject")
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
    @WithAdminUser
    void shouldKeepFailedReplayPending() throws Exception {
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "not-json", "scope-b"),
                new PayloadParsingException("invalid JSON"));
        var row = service.listWorkspace(workspace.getId(), 0).getFirst();
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(service.listWorkspace(workspace.getId(), 0).getFirst().replayedAt())
                .isNull();
    }

    @Test
    @WithAdminUser
    void shouldReplayTheStoredEnvelopeOnlyOnceAfterHandlerSucceeds() throws Exception {
        // An ignored action is a valid event: dispatch succeeds without importing any provider data.
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "{\"action\":\"ignored\"}", "scope-c"),
                new PayloadParsingException("older parser"));
        var row = service.listWorkspace(workspace.getId(), 0).getFirst();
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
        assertThat(service.listWorkspace(workspace.getId(), 0).getFirst().replayedAt())
                .isNotNull();
    }

    @Test
    @WithAdminUser
    void shouldKeepUnassignedInstallationRowsSeparateFromWorkspaceRows() throws Exception {
        service.capture(
                null,
                delivery("github.installation.123.installation", "not-json", "installation"),
                new PayloadParsingException("invalid JSON"));
        var row = service.listInstallation(0).getFirst();
        assertThat(service.listWorkspace(workspace.getId(), 0)).isEmpty();
        client.get()
                .uri("/admin/integration/dead-letters")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
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
        assertThat(service.listWorkspace(workspace.getId(), 0)).hasSize(1);
        Message redelivery = context.next(Duration.ofSeconds(5));
        assertThat(redelivery).isNotNull();
        if (redelivery == null) throw new AssertionError("Missing redelivery");
        assertThat(redelivery.metaData().streamSequence())
                .isEqualTo(first.metaData().streamSequence());
        poison.handleFailure(workspace.getId(), redelivery, new PayloadParsingException("bad"));
        assertThat(service.listWorkspace(workspace.getId(), 0)).hasSize(1);
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
        assertThat(service.listWorkspace(workspace.getId(), 0)).isEmpty();
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
        var row = service.listWorkspace(workspace.getId(), 0).getFirst();
        broker.jetStreamManagement().deleteMessage(stream, message.metaData().streamSequence());
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(410)
                .expectBody(Void.class);
        assertThat(service.listWorkspace(workspace.getId(), 0).getFirst().replayedAt())
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
        var row = service.listWorkspace(workspace.getId(), 0).getFirst();
        IntegrationMessageDispatcher original =
                (IntegrationMessageDispatcher) ReflectionTestUtils.getField(consumer, "dispatcher");
        var dispatcher = mock(IntegrationMessageDispatcher.class);
        var handler = mock(IntegrationMessageHandler.class);
        when(dispatcher.dispatch("github.acme.repo.issues")).thenReturn(Optional.of(handler));
        doAnswer(call -> {
                    workspace.setDisplayName("partial replay");
                    deadLetterWorkspaces.saveAndFlush(workspace);
                    if (illegalState) throw new IllegalStateException("failure after a database write");
                    throw new IllegalArgumentException("failure after a database write");
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
            assertThat(service.listWorkspace(workspace.getId(), 0).getFirst().replayedAt())
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
        var row = service.listWorkspace(workspace.getId(), 0).getFirst();
        ReflectionTestUtils.setField(
                consumer, "subscriptionProvider", (NatsSubscriptionProvider) scope -> Optional.empty());
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(service.listWorkspace(workspace.getId(), 0).getFirst().replayedAt())
                .isNull();
    }

    @Test
    @WithAdminUser
    void shouldExportAndEraseTheReplayActorsAttribution() throws Exception {
        service.capture(
                workspace.getId(),
                delivery("github.acme.repo.issues", "{\"action\":\"ignored\"}", "privacy"),
                new PayloadParsingException("old"));
        var row = service.listWorkspace(workspace.getId(), 0).getFirst();
        client.post()
                .uri("/workspaces/deadletters-a/integrations/dead-letters/{id}/replays", row.id())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        var replayed = service.listWorkspace(workspace.getId(), 0).getFirst();
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
        var erased = service.listWorkspace(workspace.getId(), 0).getFirst();
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
        var row = service.listInstallation(0).getFirst();
        for (int attempt = 0; attempt < 2; attempt++) {
            client.post()
                    .uri("/admin/integration/dead-letters/{id}/replays", row.id())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
        }
        var replayed = service.listInstallation(0).getFirst();
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
