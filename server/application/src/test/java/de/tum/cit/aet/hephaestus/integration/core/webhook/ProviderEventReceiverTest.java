package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.consumer.EventTraceContext;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderEventSubscriptions;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Admission decisions with the storage boundary captured; PostgreSQL tests prove locking and deduplication. */
class ProviderEventReceiverTest extends BaseUnitTest {
    private static final long WORKSPACE = 7;
    private static final long CONNECTION = 11;
    private static final byte[] BODY = "{\"private\":\"work\"}".getBytes(StandardCharsets.UTF_8);
    private final ProviderEventStore store = mock(ProviderEventStore.class);
    private final ProviderEventSubscriptions subscriptions = mock(ProviderEventSubscriptions.class);
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<ProviderEventReceiver> receivers = new ArrayList<>();

    @AfterEach
    void closeReceivers() {
        receivers.forEach(ProviderEventReceiver::close);
        meters.close();
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void shouldStoreOnlyTheVerifiedSourceNamespaceForAnActiveConnection(IntegrationKind kind) {
        Connection connection = candidate(kind, config(kind), IntegrationState.ACTIVE);
        source(kind, connection);
        var headers = new LinkedHashMap<>(headers(kind));
        headers.put(ProviderEvent.SOURCE_SERVER_URL, "https://forged.example.test");
        when(store.store(anyList())).thenReturn(1);

        assertThat(receiver().receive(kind, subject(kind), "delivery", headers, BODY))
                .isOne();

        ProviderEventStore.Arrival arrival = onlyArrival();
        assertThat(arrival.workspaceId()).isEqualTo(WORKSPACE);
        assertThat(arrival.provider()).isEqualTo(kind.name().toLowerCase(Locale.ROOT));
        assertThat(arrival.subject()).isEqualTo(subject(kind));
        assertThat(arrival.deliveryKey()).isEqualTo("delivery");
        assertThat(arrival.payload()).isEqualTo(BODY);
        assertThat(arrival.headers()).containsEntry(ProviderEvent.SOURCE_SERVER_URL, namespace(kind));
        assertThat(headers).containsEntry(ProviderEvent.SOURCE_SERVER_URL, "https://forged.example.test");
    }

    @ParameterizedTest
    @MethodSource("inactiveConnections")
    void shouldRecordTheReasonAndStoreNoPayloadForEveryInactiveState(IntegrationKind kind, IntegrationState state) {
        Connection connection = candidate(kind, config(kind), state);
        source(kind, connection);

        assertThat(receiver().receive(kind, subject(kind), "delivery", headers(kind), BODY))
                .isZero();

        rejected(kind, "connection-not-active");
        noPayload();
    }

    private static Stream<Arguments> inactiveConnections() {
        return Stream.of(IntegrationKind.values())
                .flatMap(kind -> Stream.of(IntegrationState.values())
                        .filter(state -> state != IntegrationState.ACTIVE)
                        .map(state -> Arguments.of(kind, state)));
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void shouldRefuseAConnectionRetaggedToAnotherProvider(IntegrationKind kind) {
        Connection connection = candidate(kind, config(kind), IntegrationState.ACTIVE);
        source(kind, connection);
        doAnswer(ignored -> {
                    ReflectionTestUtils.setField(
                            connection,
                            "kind",
                            kind == IntegrationKind.SLACK ? IntegrationKind.OUTLINE : IntegrationKind.SLACK);
                    return null;
                })
                .when(entities)
                .refresh(connection);

        assertThat(receiver().receive(kind, subject(kind), "delivery", headers(kind), BODY))
                .isZero();

        rejected(kind, "connection-not-active");
        noPayload();
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void shouldRetrySelectionWithoutStoringTheOldRouteWhenConfigurationChangesUnderTheLock(IntegrationKind kind) {
        Connection connection = candidate(kind, config(kind), IntegrationState.ACTIVE);
        source(kind, connection);
        doAnswer(ignored -> {
                    ReflectionTestUtils.setField(connection, "config", changedConfig(kind));
                    return null;
                })
                .when(entities)
                .refresh(connection);

        assertThatThrownBy(() -> receiver().receive(kind, subject(kind), "delivery", headers(kind), BODY))
                .isInstanceOf(TransientDataAccessResourceException.class)
                .hasMessage("Connection changed during webhook admission");

        verify(store, never()).store(anyList());
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"SLACK", "GITLAB", "OUTLINE"})
    void shouldRecordAMissingConnectionWithoutRefreshingAnEntityDeletedBeforeTheLock(IntegrationKind kind) {
        Connection connection = candidate(kind, config(kind), IntegrationState.ACTIVE);
        when(connections.findAndLockLifecycleConnection(CONNECTION, WORKSPACE)).thenReturn(Optional.empty());

        assertThat(receiver().receive(kind, subject(kind), "delivery", headers(kind), BODY))
                .isZero();

        rejected(kind, "connection-missing");
        verify(entities, never()).refresh(connection);
        noPayload();
    }

    @Test
    void shouldDropAStaleSubscriptionWhoseConnectionNoLongerExists() {
        when(subscriptions.subscribers(subject(IntegrationKind.SLACK)))
                .thenReturn(List.of(new ProviderEventSubscriptions.Subscription(WORKSPACE, CONNECTION)));
        assertThat(receiver()
                        .receive(IntegrationKind.SLACK, subject(IntegrationKind.SLACK), "delivery", Map.of(), BODY))
                .isZero();
        rejected(IntegrationKind.SLACK, "connection-missing");
        noPayload();
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"SLACK", "GITLAB", "OUTLINE"})
    void shouldDropAnUnsubscribedDeliveryWithoutKeepingItsPayload(IntegrationKind kind) {
        assertThat(receiver().receive(kind, subject(kind), "delivery", headers(kind), BODY))
                .isZero();
        rejected(kind, "no-subscription");
        noPayload();
    }

    @ParameterizedTest
    @MethodSource("wrongRoutes")
    void shouldRejectVerifiedRouteMetadataThatDoesNotMatchTheLockedConnection(
            IntegrationKind kind, ConnectionConfig config, Map<String, String> headers) {
        Connection connection = candidate(kind, config, IntegrationState.ACTIVE);
        source(kind, connection);

        assertThat(receiver().receive(kind, subject(kind), "delivery", headers, BODY))
                .isZero();

        rejected(kind, "connection-route-mismatch");
        noPayload();
    }

    private static Stream<Arguments> wrongRoutes() {
        var gitlab = headers(IntegrationKind.GITLAB);
        return Stream.of(
                Arguments.of(
                        IntegrationKind.GITHUB,
                        new ConnectionConfig.GitHubPatConfig("org", "https://other.example.test", Set.of()),
                        headers(IntegrationKind.GITHUB)),
                Arguments.of(
                        IntegrationKind.GITHUB,
                        new ConnectionConfig.GitHubAppConfig(99L, "org", null, Set.of()),
                        headers(IntegrationKind.GITHUB)),
                Arguments.of(
                        IntegrationKind.GITHUB,
                        new ConnectionConfig.GitHubAppConfig(null, "org", null, Set.of()),
                        headers(IntegrationKind.GITHUB)),
                Arguments.of(IntegrationKind.GITHUB, config(IntegrationKind.OUTLINE), headers(IntegrationKind.GITHUB)),
                Arguments.of(IntegrationKind.GITLAB, config(IntegrationKind.SLACK), gitlab),
                Arguments.of(IntegrationKind.GITLAB, config(IntegrationKind.GITLAB), Map.of()),
                Arguments.of(
                        IntegrationKind.GITLAB,
                        config(IntegrationKind.GITLAB),
                        Map.of(
                                GitLabRouteCredential.HEADER_WORKSPACE,
                                "7",
                                GitLabRouteCredential.HEADER_ORIGIN,
                                "https://other.example.test",
                                GitLabRouteCredential.HEADER_GROUP_ID,
                                "31")),
                Arguments.of(
                        IntegrationKind.GITLAB,
                        config(IntegrationKind.GITLAB),
                        Map.of(
                                GitLabRouteCredential.HEADER_WORKSPACE,
                                "7",
                                GitLabRouteCredential.HEADER_ORIGIN,
                                "https://gitlab.example.test",
                                GitLabRouteCredential.HEADER_GROUP_ID,
                                "99")),
                Arguments.of(IntegrationKind.OUTLINE, config(IntegrationKind.SLACK), headers(IntegrationKind.OUTLINE)),
                Arguments.of(
                        IntegrationKind.OUTLINE,
                        new ConnectionConfig.OutlineConfig("https://outline.example.test", "sub", null, Set.of()),
                        headers(IntegrationKind.OUTLINE)),
                Arguments.of(IntegrationKind.OUTLINE, config(IntegrationKind.OUTLINE), Map.of()));
    }

    @Test
    void shouldRequireAnActiveSourceInstallationEvenForPatFanout() {
        candidate(
                IntegrationKind.GITHUB,
                new ConnectionConfig.GitHubPatConfig("org", null, Set.of()),
                IntegrationState.ACTIVE);
        assertThat(receiver()
                        .receive(
                                IntegrationKind.GITHUB,
                                subject(IntegrationKind.GITHUB),
                                "delivery",
                                headers(IntegrationKind.GITHUB),
                                BODY))
                .isZero();
        rejected(IntegrationKind.GITHUB, "connection-not-active");
        verify(store, never()).store(anyList());
    }

    @Test
    void shouldStorePatFanoutWhenTheSignedSourceInstallationIsActive() {
        candidate(
                IntegrationKind.GITHUB,
                new ConnectionConfig.GitHubPatConfig("org", null, Set.of()),
                IntegrationState.ACTIVE);
        source(IntegrationKind.GITHUB, activeSource());
        when(store.store(anyList())).thenReturn(1);
        assertThat(receiver()
                        .receive(
                                IntegrationKind.GITHUB,
                                subject(IntegrationKind.GITHUB),
                                "delivery",
                                headers(IntegrationKind.GITHUB),
                                BODY))
                .isOne();
        assertThat(onlyArrival().workspaceId()).isEqualTo(WORKSPACE);
    }

    @Test
    void shouldRejectWorkWithoutTheSignedInstallationBeforeLookingForPatSubscribers() {
        assertThat(receiver()
                        .receive(IntegrationKind.GITHUB, subject(IntegrationKind.GITHUB), "delivery", Map.of(), BODY))
                .isZero();
        rejected(IntegrationKind.GITHUB, "work-event-without-source");
        verify(subscriptions, never()).subscribers(any());
        verify(store, never()).store(anyList());
    }

    @Test
    void shouldRejectAnUnscopedGithubWorkEventEvenWithAnInstallationId() {
        assertThat(receiver()
                        .receive(
                                IntegrationKind.GITHUB,
                                "github.?.?.issues",
                                "delivery",
                                headers(IntegrationKind.GITHUB),
                                BODY))
                .isZero();
        rejected(IntegrationKind.GITHUB, "work-event-without-source");
        verify(store, never()).store(anyList());
    }

    @Test
    void shouldStoreInstallationControlWithoutAWorkspaceOrSubscription() {
        when(store.store(anyList())).thenReturn(1);
        assertThat(receiver().receive(IntegrationKind.GITHUB, "github.?.?.installation", "control", Map.of(), BODY))
                .isOne();
        var arrival = onlyArrival();
        assertThat(arrival.workspaceId()).isNull();
        assertThat(arrival.headers()).containsEntry(ProviderEvent.SOURCE_SERVER_URL, "https://github.com");
        verify(subscriptions, never()).subscribers(any());
    }

    @Test
    void shouldReturnTheStorageResultWhenADeliveryWasAlreadyStored() {
        candidate(IntegrationKind.SLACK, config(IntegrationKind.SLACK), IntegrationState.ACTIVE);
        assertThat(receiver()
                        .receive(
                                IntegrationKind.SLACK,
                                subject(IntegrationKind.SLACK),
                                "duplicate",
                                headers(IntegrationKind.SLACK),
                                BODY))
                .isZero();
        assertThat(onlyArrival().deliveryKey()).isEqualTo("duplicate");
        assertThat(meters.find(IntegrationCoreMetrics.WEBHOOK_REJECTED).counter())
                .isNull();
    }

    @Test
    void shouldPropagateTheDatabaseFailureSoThePipelineCanReturn503() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(subscriptions.subscribers(subject(IntegrationKind.SLACK))).thenThrow(failure);
        assertThatThrownBy(() -> receiver()
                        .receive(IntegrationKind.SLACK, subject(IntegrationKind.SLACK), "delivery", Map.of(), BODY))
                .isSameAs(failure);
        verify(store, never()).store(anyList());
    }

    private ProviderEventReceiver receiver() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        var receiver = new ProviderEventReceiver(
                store,
                subscriptions,
                connections,
                meters,
                mock(EventTraceContext.class),
                new TransactionTemplate(manager));
        ReflectionTestUtils.setField(receiver, "entityManager", entities);
        receivers.add(receiver);
        return receiver;
    }

    private Connection candidate(IntegrationKind kind, ConnectionConfig config, IntegrationState state) {
        var connection = connection(CONNECTION, kind, config, state);
        when(subscriptions.subscribers(subject(kind)))
                .thenReturn(List.of(new ProviderEventSubscriptions.Subscription(WORKSPACE, CONNECTION)));
        when(connections.findByIdAndWorkspaceId(CONNECTION, WORKSPACE)).thenReturn(Optional.of(connection));
        when(connections.findAndLockLifecycleConnection(CONNECTION, WORKSPACE)).thenReturn(Optional.of(CONNECTION));
        return connection;
    }

    private static Connection connection(
            long id, IntegrationKind kind, ConnectionConfig config, IntegrationState state) {
        var workspace = new Workspace();
        workspace.setId(WORKSPACE);
        var connection = new Connection(workspace, kind, "17", config);
        ReflectionTestUtils.setField(connection, "id", id);
        connection.setState(state);
        return connection;
    }

    private static Connection activeSource() {
        return connection(19, IntegrationKind.GITHUB, config(IntegrationKind.GITHUB), IntegrationState.ACTIVE);
    }

    private void source(IntegrationKind kind, Connection connection) {
        if (kind != IntegrationKind.GITHUB) return;
        // A distinct source row permits PAT fan-out while each destination route is checked separately.
        Connection source = connection.getConfig() instanceof ConnectionConfig.GitHubAppConfig app
                        && Objects.equals(app.installationId(), 17L)
                ? connection
                : activeSource();
        when(connections.findAllByKindAndInstanceKeyAndStateIn(eq(kind), eq("17"), anyList()))
                .thenReturn(List.of(source));
        if (!Objects.equals(source.getId(), CONNECTION)) {
            when(connections.findAndLockLifecycleConnection(19, WORKSPACE)).thenReturn(Optional.of(19L));
        }
    }

    private void rejected(IntegrationKind kind, String reason) {
        assertThat(meters.get(IntegrationCoreMetrics.WEBHOOK_REJECTED)
                        .tags("provider", kind.name().toLowerCase(Locale.ROOT), "reason", reason)
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    private void noPayload() {
        mockingDetails(store).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("store"))
                .forEach(invocation -> {
                    List<?> stored = invocation.getArgument(0);
                    assertThat(stored).isEmpty();
                });
    }

    private ProviderEventStore.Arrival onlyArrival() {
        var captor = arrivals();
        verify(store).store(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        return captor.getValue().getFirst();
    }

    private static ArgumentCaptor<List<ProviderEventStore.Arrival>> arrivals() {
        return ArgumentCaptor.captor();
    }

    private static ConnectionConfig config(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> new ConnectionConfig.GitHubAppConfig(17L, "org", null, Set.of());
            case GITLAB -> new ConnectionConfig.GitLabConfig("https://gitlab.example.test", 31L, 41L, Set.of(), null);
            case SLACK -> new ConnectionConfig.SlackConfig("team", null, null, Set.of());
            case OUTLINE ->
                new ConnectionConfig.OutlineConfig("https://outline.example.test", "sub", "key/route", Set.of());
        };
    }

    private static ConnectionConfig changedConfig(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> new ConnectionConfig.GitHubAppConfig(17L, "new-org", null, Set.of());
            case GITLAB -> new ConnectionConfig.GitLabConfig("https://gitlab.example.test", 32L, 41L, Set.of(), null);
            case SLACK -> new ConnectionConfig.SlackConfig("new-team", null, null, Set.of());
            case OUTLINE ->
                new ConnectionConfig.OutlineConfig("https://outline.example.test", "sub", "new/route", Set.of());
        };
    }

    private static String subject(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> "github.org.repo.issues";
            case GITLAB -> "gitlab.?connection.11.push";
            case SLACK -> "slack.team.message";
            case OUTLINE -> "outline.sub.documents~update";
        };
    }

    private static String namespace(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> "https://github.com";
            case GITLAB -> "https://gitlab.example.test";
            case SLACK -> "https://slack.com";
            case OUTLINE -> "https://outline.example.test";
        };
    }

    private static Map<String, String> headers(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> Map.of(ProviderEvent.GITHUB_INSTALLATION_ID, "17");
            case GITLAB ->
                Map.of(
                        GitLabRouteCredential.HEADER_WORKSPACE,
                        "7",
                        GitLabRouteCredential.HEADER_ORIGIN,
                        "https://gitlab.example.test",
                        GitLabRouteCredential.HEADER_GROUP_ID,
                        "31");
            case SLACK -> Map.of();
            case OUTLINE -> Map.of(OutlineWebhookController.HEADER_ROUTE, "key/route");
        };
    }
}
