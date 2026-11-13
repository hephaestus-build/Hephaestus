package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.ConnectionAdminService;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionCredentialsReplacedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import de.tum.cit.aet.hephaestus.integration.outline.lifecycle.OutlineResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.connect.GitHubResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect.GitLabResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthException;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackResourceAdapter;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.Serial;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Real adapter boundaries and PostgreSQL coordination, with deterministic provider transport fakes. */
class ProviderResourceAdoptionIntegrationTest extends BaseIntegrationTest {
    private static final PublicApiUrls URLS =
            new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create("https://receiver.example.test"), "/api"));

    @Autowired
    private ConnectionAdminService connectionAdmin;

    @Autowired
    private ProviderResourceRepairRepository rows;

    @Autowired
    private ConnectionProviderRepairRepository references;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private CredentialReader credentials;

    @Autowired
    private PlatformTransactionManager manager;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private @Nullable EntityManager entities;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeOwnFences() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            for (UUID id : created) {
                references.deleteAll(references.findByRepair_Id(id));
                rows.deleteById(id);
            }
        });
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void anAcceptedLateRemovalCannotDestroyAnAdmittedReplacement(IntegrationKind kind) {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        List<UUID> pending = request(context, old);
        assertThat(pending).isNotEmpty();
        pending.forEach(context.dispatcher()::dispatch);
        assertThat(provider.accepted).containsExactly(provider.oldRemoteKey());
        assertThatThrownBy(() -> adopt(context, provider, false, false)).isInstanceOf(ResponseStatusException.class);
        if (kind == IntegrationKind.SLACK) {
            assertThatThrownBy(() -> adopt(context, provider, true, true)).isInstanceOf(ResponseStatusException.class);
        }
        Connection fresh = adopt(context, provider, true, false);
        provider.live.add(provider.freshRemoteKey());
        provider.completeAccepted();
        context.clock().now = context.clock().now.plusSeconds(61);
        pending.forEach(context.dispatcher()::dispatch);
        assertThat(provider.live).containsExactly(provider.freshRemoteKey());
        assertThat(provider.accepted).containsExactly(provider.oldRemoteKey());
        assertThat(connections
                        .findByIdAndWorkspaceId(
                                fresh.getId(), fresh.getWorkspace().getId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(IntegrationState.ACTIVE);
        assertThatThrownBy(() -> adopt(context, provider, false, false)).isInstanceOf(ResponseStatusException.class);
        if (kind == IntegrationKind.SLACK) {
            assertThat(provider.botActive.get(provider.oldBot())).isFalse();
            assertThat(provider.botActive.get(provider.freshBot())).isTrue();
            assertThat(provider.botMemberships.get(provider.oldBot())).isEmpty();
            assertThat(provider.botMemberships.get(provider.freshBot())).containsExactly("fresh-channel");
        }
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void aLiveOwnerInAnotherWorkspacePreventsRemoval(IntegrationKind kind) {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        // Slack's replacement token still holds the same bot user. Other adapters share the exact resource.
        Connection holder =
                owner(provider, kind == IntegrationKind.SLACK, kind == IntegrationKind.SLACK, IntegrationState.ACTIVE);
        assertThat(request(context, old)).isEmpty();
        assertThat(provider.accepted).isEmpty();
        assertThat(connections
                        .findByIdAndWorkspaceId(
                                holder.getId(), holder.getWorkspace().getId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(IntegrationState.ACTIVE);
    }

    @Test
    void shouldRepairALostOutlineSuspendWakeAndRetryItsLostFirstDelete() {
        Wire provider = new Wire(IntegrationKind.OUTLINE);
        Context context = context(provider.adapter);
        Connection owner = owner(provider, false, false, IntegrationState.SUSPENDED);
        // The state committed without a wake or an existing fence. The durable sweep discovers it.
        assertThat(context.repairs().suspendedOutlineHooks())
                .anyMatch(candidate -> candidate.connectionId() == owner.getId());
        provider.deleteUnavailable = true;
        context.dispatcher().reconcile();
        List<UUID> pending =
                references
                        .findByWorkspaceIdAndConnection_Id(owner.getWorkspace().getId(), owner.getId())
                        .stream()
                        .map(ref -> ref.getRepair().getId())
                        .toList();
        created.addAll(pending);
        assertThat(provider.accepted).isEmpty();
        assertThat(provider.live).contains(provider.oldRemoteKey());
        Connection fresh = adopt(context, provider, true, false);
        provider.live.add(provider.freshRemoteKey());
        provider.deleteUnavailable = false;
        provider.terminal = true;
        context.clock().now = context.clock().now.plusSeconds(61);
        context.dispatcher().reconcile();
        assertThat(provider.live).doesNotContain(provider.oldRemoteKey()).contains(provider.freshRemoteKey());
        assertThat(connections.findById(fresh.getId()).orElseThrow().getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(pending)
                .allSatisfy(id -> assertThat(rows.findById(id).orElseThrow().getState())
                        .isEqualTo(ProviderResourceRepair.State.CONFIRMATION_REQUIRED));
        assertThat(((ConnectionConfig.OutlineConfig) connections
                                .findByIdAndWorkspaceId(
                                        owner.getId(), owner.getWorkspace().getId())
                                .orElseThrow()
                                .getConfig())
                        .webhookSubscriptionId())
                .isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITLAB", "OUTLINE"})
    void shouldRemoveARegistrationThatReturnsAfterUninstall(IntegrationKind kind) {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        Connection owner = owner(provider, false, false, IntegrationState.ACTIVE);
        String marker = UUID.randomUUID().toString();
        Resource registration = new Resource(
                kind,
                provider.origin(),
                ProviderResourceAdapter.ResourceType.REGISTRATION,
                kind == IntegrationKind.GITLAB ? "99" : "",
                marker,
                ProviderResourceAdapter.fingerprint(provider.hookUrl(false)));
        UUID intent = Objects.requireNonNull(context.transactions()
                .execute(status -> context.repairs()
                        .beginRegistration(owner.getWorkspace().getId(), owner.getId(), registration)));
        created.add(intent);
        context.transactions().executeWithoutResult(status -> {
            Connection current = connections
                    .findByIdAndWorkspaceId(owner.getId(), owner.getWorkspace().getId())
                    .orElseThrow();
            current.setState(IntegrationState.UNINSTALLED);
        });
        Boolean adopted = context.transactions()
                .execute(status -> context.repairs()
                        .completeRegistration(
                                owner.getWorkspace().getId(),
                                owner.getId(),
                                intent,
                                ignored -> provider.config(false, false)));
        assertThat(adopted).isFalse();
        List<UUID> cleanup =
                references
                        .findByWorkspaceIdAndConnection_Id(owner.getWorkspace().getId(), owner.getId())
                        .stream()
                        .map(ref -> ref.getRepair().getId())
                        .toList();
        created.addAll(cleanup);
        assertThat(rows.existsById(intent)).isFalse();
        assertThat(cleanup).isNotEmpty();
        provider.terminal = true;
        cleanup.forEach(context.dispatcher()::dispatch);
        assertThat(provider.live).doesNotContain(provider.oldRemoteKey());
        assertThat(connections
                        .findByIdAndWorkspaceId(
                                owner.getId(), owner.getWorkspace().getId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(IntegrationState.UNINSTALLED);
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void shouldSerializeCrossWorkspaceAdoptionBehindCapturedRemoval(IntegrationKind kind) throws Exception {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        HeldTransaction capture = HeldTransaction.holdIn(context.transactions(), jdbc, () -> request(context, old));
        var adopting = CompletableFuture.supplyAsync(() -> adopt(context, provider, false, false));
        capture.commitAfter(adopting, () -> assertThat(adopting.isDone()).isFalse());
        assertThatThrownBy(() -> adopting.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(ResponseStatusException.class);
        assertThat(provider.accepted).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void shouldObserveACrossWorkspaceOwnerThatCommitsBeforeRemoval(IntegrationKind kind) throws Exception {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        HeldTransaction adoption =
                HeldTransaction.holdIn(context.transactions(), jdbc, () -> adopt(context, provider, false, false));
        var capture = CompletableFuture.supplyAsync(() -> request(context, old));
        adoption.commitAfter(capture, () -> assertThat(capture.isDone()).isFalse());
        assertThat(capture.get(20, TimeUnit.SECONDS)).isEmpty();
        assertThat(provider.accepted).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void shouldRetainEveryFenceWhenTheProviderHidesItsTargetBehind404(IntegrationKind kind) {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        List<UUID> pending = request(context, old);
        provider.readUnavailable = true;
        pending.forEach(context.dispatcher()::dispatch);
        assertThat(pending).allMatch(rows::existsById);
        assertThat(provider.accepted).isEmpty();
        assertThatThrownBy(() -> adopt(context, provider, false, false)).isInstanceOf(ResponseStatusException.class);
        assertThat(adopt(context, provider, true, false).getState()).isEqualTo(IntegrationState.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void shouldResumeEachAdapterAfterACrashBeforeDispatchWithoutLosingItsFence(IntegrationKind kind) {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        List<UUID> pending = request(context, owner(provider, false, false, IntegrationState.UNINSTALLED));
        provider.crashBeforeRead = true;
        assertThatThrownBy(() -> pending.forEach(context.dispatcher()::dispatch))
                .isInstanceOf(SimulatedCrash.class);
        assertThat(provider.accepted).isEmpty();
        assertThatThrownBy(() -> adopt(context, provider, false, false)).isInstanceOf(ResponseStatusException.class);
        adopt(context, provider, true, false);
        provider.live.add(provider.freshRemoteKey());
        context.clock().now = context.clock().now.plusSeconds(601);
        pending.forEach(context.dispatcher()::dispatch);
        assertThat(provider.accepted).containsExactly(provider.oldRemoteKey());
        provider.completeAccepted();
        assertThat(provider.live).containsExactly(provider.freshRemoteKey());
    }

    @ParameterizedTest
    @EnumSource(IntegrationKind.class)
    void shouldRetainEachAdaptersFenceAfterACrashFollowingRemoteDispatch(IntegrationKind kind) {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        List<UUID> pending = request(context, owner(provider, false, false, IntegrationState.UNINSTALLED));
        provider.crashAfterAccept = true;
        assertThatThrownBy(() -> pending.forEach(context.dispatcher()::dispatch))
                .isInstanceOf(SimulatedCrash.class);
        assertThat(provider.accepted).containsExactly(provider.oldRemoteKey());
        assertThatThrownBy(() -> adopt(context, provider, false, false)).isInstanceOf(ResponseStatusException.class);
        adopt(context, provider, true, false);
        provider.live.add(provider.freshRemoteKey());
        provider.completeAccepted();
        context.clock().now = context.clock().now.plusSeconds(601);
        pending.forEach(context.dispatcher()::dispatch);
        assertThat(provider.accepted).containsExactly(provider.oldRemoteKey());
        assertThat(provider.live).containsExactly(provider.freshRemoteKey());
        assertThatThrownBy(() -> adopt(context, provider, false, false)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITLAB", "OUTLINE"})
    void shouldRefuseALegacySuspendedIdentityBeforeTheFirstRepairSweep(IntegrationKind kind) {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        owner(provider, false, false, IntegrationState.SUSPENDED);
        assertThatThrownBy(() -> adopt(context, provider, false, false)).isInstanceOf(ResponseStatusException.class);
        assertThat(provider.accepted).isEmpty();
        assertThat(adopt(context, provider, true, false).getState()).isEqualTo(IntegrationState.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITLAB"})
    void shouldWaitForTheResourceIdentityBeforeLockingTheConnectionForReconnect(IntegrationKind kind) throws Exception {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        HeldTransaction identity = HeldTransaction.holdIn(
                context.transactions(),
                jdbc,
                () -> context.repairs()
                        .coordinate(
                                context.repairs().resources(ProviderResourceRepairs.ref(old), old.getConfig(), null)));
        var reconnect = CompletableFuture.supplyAsync(() -> connectionAdmin.createInlineConnection(
                old.getWorkspace().getId(),
                IntegrationKind.GITLAB,
                old.getInstanceKey(),
                new BearerToken("fresh-credential-" + old.getId(), null),
                Map.of(),
                null));
        identity.commitAfter(
                reconnect,
                () -> new TransactionTemplate(manager)
                        .executeWithoutResult(status -> assertThat(jdbc.queryForObject(
                                        "SELECT id FROM connection WHERE workspace_id = ? AND id = ? FOR UPDATE NOWAIT",
                                        Long.class,
                                        old.getWorkspace().getId(),
                                        old.getId()))
                                .isEqualTo(old.getId())));
        assertThat(reconnect.get(20, TimeUnit.SECONDS).getState()).isEqualTo(IntegrationState.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldResolveAuthenticatedAppAbsenceOnlyBeforeAnyPossibleDispatch(boolean previouslyDispatched) {
        Wire provider = new Wire(IntegrationKind.GITHUB);
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        UUID id = request(context, old).getFirst();
        if (previouslyDispatched) {
            context.dispatcher().dispatch(id);
            assertThat(provider.accepted).containsExactly(provider.oldRemoteKey());
            context.clock().now = context.clock().now.plusSeconds(61);
        }
        provider.live.remove(provider.oldRemoteKey());
        context.dispatcher().dispatch(id);
        if (previouslyDispatched) {
            assertThat(rows.findById(id).orElseThrow().getState())
                    .isEqualTo(ProviderResourceRepair.State.CONFIRMATION_REQUIRED);
        } else {
            assertThat(rows.existsById(id)).isFalse();
            assertThat(provider.accepted).isEmpty();
        }
    }

    @Test
    void shouldRetryOnlyTheConnectionWhoseAuthorizationWasRestoredWithoutWaitingForBackoff() {
        Wire provider = new Wire(IntegrationKind.OUTLINE);
        provider.terminal = true;
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        UUID id = request(context, old).getFirst();
        Wire unrelated = new Wire(IntegrationKind.OUTLINE);
        UUID other = request(context, owner(unrelated, false, false, IntegrationState.UNINSTALLED))
                .getFirst();
        context.transactions().executeWithoutResult(status -> {
            for (UUID rowId : List.of(id, other)) {
                var row = rows.findById(rowId).orElseThrow();
                for (int i = 0; i < 7; i++) {
                    row.transition(
                            ProviderResourceRepair.State.CHECKING,
                            context.clock().instant());
                    row.transition(
                            ProviderResourceRepair.State.NEEDS_AUTHORIZATION,
                            context.clock().instant());
                }
            }
        });
        Instant delayed = rows.findById(other).orElseThrow().getNextAttemptAt();
        context.dispatcher().dispatch(id);
        assertThat(provider.accepted).isEmpty();
        var restored = new ConnectionCredentialsReplacedEvent(
                old.getId(), old.getWorkspace().getId(), old.getKind());
        context.transactions()
                .executeWithoutResult(status -> rows.findById(id)
                        .orElseThrow()
                        .transition(
                                ProviderResourceRepair.State.CHECKING,
                                context.clock().instant()));
        assertThatThrownBy(() -> context.dispatcher().on(restored))
                .isInstanceOf(TransientDataAccessResourceException.class);
        assertThat(provider.accepted).isEmpty();
        context.transactions()
                .executeWithoutResult(status -> rows.findById(id)
                        .orElseThrow()
                        .transition(
                                ProviderResourceRepair.State.NEEDS_AUTHORIZATION,
                                context.clock().instant()));
        context.dispatcher().on(restored);
        assertThat(rows.existsById(id)).isFalse();
        assertThat(provider.live).doesNotContain(provider.oldRemoteKey());
        assertThat(rows.findById(other).orElseThrow().getNextAttemptAt()).isEqualTo(delayed);
        assertThat(rows.findById(other).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.NEEDS_AUTHORIZATION);
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationKind.class,
            names = {"GITLAB", "OUTLINE"})
    void shouldWakeRemovalThroughAnActualInlineReconnect(IntegrationKind kind) {
        Wire provider = new Wire(kind);
        Context context = context(provider.adapter);
        Connection old = Objects.requireNonNull(context.transactions().execute(status -> {
            var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("inline-wake-" + UUID.randomUUID()));
            String instance = kind == IntegrationKind.GITLAB ? "99" : provider.origin();
            var owner = new Connection(workspace, kind, instance, provider.config(false, false));
            owner.setState(IntegrationState.UNINSTALLED);
            owner.setCredentials(provider.credential(false), converter);
            return connections.saveAndFlush(owner);
        }));
        UUID id = request(context, old).getFirst();
        jdbc.update(
                "UPDATE provider_resource_repair SET state='NEEDS_AUTHORIZATION', next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id=?",
                id);
        long before = rows.findById(id).orElseThrow().getAttemptEpoch();
        long generation = old.getCredentialsGeneration();

        Connection reconnected = connectionAdmin.createInlineConnection(
                old.getWorkspace().getId(),
                kind,
                old.getInstanceKey(),
                new BearerToken("restored-" + UUID.randomUUID(), null),
                Map.of("server_url", provider.origin()),
                null);

        assertThat(reconnected.getId()).isEqualTo(old.getId());
        assertThat(reconnected.getCredentialsGeneration()).isGreaterThan(generation);
        // No manual event or listener call: the real reconnect transaction publishes the durable wake.
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(rows.findById(id)
                                .map(row -> row.getAttemptEpoch() > before)
                                .orElse(true))
                        .isTrue());
    }

    @Test
    void shouldWakeRemovalEvenWhileRegistrationDiscoveryKeepsFailing() {
        Wire provider = new Wire(IntegrationKind.OUTLINE);
        provider.terminal = true;
        Context context = context(provider.adapter);
        Connection old = owner(provider, false, false, IntegrationState.UNINSTALLED);
        UUID id = request(context, old).getFirst();
        UUID registration = Objects.requireNonNull(context.transactions().execute(status -> {
            var marker = new Resource(
                    IntegrationKind.OUTLINE,
                    provider.origin(),
                    ProviderResourceAdapter.ResourceType.REGISTRATION,
                    "subscriptions",
                    UUID.randomUUID().toString(),
                    ProviderResourceAdapter.fingerprint(provider.hookUrl(false)));
            var row = rows.saveAndFlush(
                    new ProviderResourceRepair(marker, context.clock().instant()));
            references.save(new ConnectionProviderRepair(old, row));
            return row.getId();
        }));
        context.clock().now = context.clock().instant().plusSeconds(601);
        provider.readUnavailable = true;
        context.dispatcher().dispatch(registration);
        assertThat(rows.findById(registration).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.CHECKING);
        provider.readUnavailable = false;
        context.transactions().executeWithoutResult(status -> {
            var removal = rows.findById(id).orElseThrow();
            removal.transition(
                    ProviderResourceRepair.State.CHECKING, context.clock().instant());
            removal.transition(
                    ProviderResourceRepair.State.NEEDS_AUTHORIZATION,
                    context.clock().instant());
        });

        context.dispatcher()
                .on(new ConnectionCredentialsReplacedEvent(
                        old.getId(), old.getWorkspace().getId(), old.getKind()));

        assertThat(rows.existsById(id)).isFalse();
        assertThat(rows.findById(registration).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.CHECKING);
    }

    private List<UUID> request(Context context, Connection owner) {
        List<UUID> ids = context.transactions().execute(status -> {
            Connection current = connections
                    .findByIdAndWorkspaceId(owner.getId(), owner.getWorkspace().getId())
                    .orElseThrow();
            context.repairs().requestRemoval(current, false);
            return references
                    .findByWorkspaceIdAndConnection_Id(owner.getWorkspace().getId(), owner.getId())
                    .stream()
                    .map(reference -> reference.getRepair().getId())
                    .toList();
        });
        created.addAll(Objects.requireNonNull(ids));
        return ids;
    }

    private Connection adopt(Context context, Wire provider, boolean fresh, boolean sameBot) {
        return Objects.requireNonNull(context.transactions().execute(status -> {
            var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                    "adopt-" + UUID.randomUUID().toString().substring(0, 8)));
            ConnectionConfig config = provider.config(fresh, sameBot);
            CredentialBundle credential = provider.credential(fresh);
            context.repairs()
                    .requireAdoptable(
                            new IntegrationRef(provider.kind, workspace.getId(), provider.instanceKey(fresh)),
                            config,
                            credential);
            Connection connection = new Connection(workspace, provider.kind, provider.instanceKey(fresh), config);
            connection.setCredentials(credential, converter);
            connection.setState(IntegrationState.ACTIVE);
            return connections.saveAndFlush(connection);
        }));
    }

    private Connection owner(Wire provider, boolean fresh, boolean sameBot, IntegrationState state) {
        return Objects.requireNonNull(new TransactionTemplate(manager).execute(status -> {
            var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                    "repair-owner-" + UUID.randomUUID().toString().substring(0, 8)));
            Connection connection = new Connection(
                    workspace, provider.kind, provider.instanceKey(fresh), provider.config(fresh, sameBot));
            connection.setCredentials(provider.credential(fresh), converter);
            connection.setState(state);
            return connections.saveAndFlush(connection);
        }));
    }

    private Context context(ProviderResourceAdapter adapter) {
        var factory = new StaticListableBeanFactory();
        factory.addBean("provider", adapter);
        TestClock clock = new TestClock();
        ProviderResourceRepairs coordination = new ProviderResourceRepairs(
                rows,
                references,
                connections,
                credentials,
                factory.getBeanProvider(ProviderResourceAdapter.class),
                event -> {},
                clock);
        ReflectionTestUtils.setField(coordination, "entityManager", Objects.requireNonNull(entities));
        var transactionsAdvice = new TransactionInterceptor();
        transactionsAdvice.setTransactionManager(manager);
        transactionsAdvice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(coordination);
        proxy.addAdvice(transactionsAdvice);
        ProviderResourceRepairs transactionalCoordination = (ProviderResourceRepairs) proxy.getProxy();
        ProviderResourceRepairDispatcher dispatcher = new ProviderResourceRepairDispatcher(
                rows, references, transactionalCoordination, manager, clock, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(dispatcher, "entityManager", Objects.requireNonNull(entities));
        return new Context(coordination, dispatcher, new TransactionTemplate(manager), clock);
    }

    private record Context(
            ProviderResourceRepairs repairs,
            ProviderResourceRepairDispatcher dispatcher,
            TransactionTemplate transactions,
            TestClock clock) {}

    private static final class Wire {
        final IntegrationKind kind;
        final long oldId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), Long.MAX_VALUE - 2) + 1;
        final long freshId = oldId + 1;
        final String oldRoute = UUID.randomUUID().toString();
        final String freshRoute = UUID.randomUUID().toString();
        final Set<String> live = ConcurrentHashMap.newKeySet();
        final List<String> accepted = new CopyOnWriteArrayList<>();
        final Map<String, Set<String>> botMemberships = new ConcurrentHashMap<>();
        final Map<String, Boolean> botActive = new ConcurrentHashMap<>();
        final ProviderResourceAdapter adapter;
        boolean terminal;
        boolean crashBeforeRead;
        boolean crashAfterAccept;
        boolean readUnavailable;
        boolean deleteUnavailable;

        Wire(IntegrationKind kind) {
            this.kind = kind;
            live.add(oldRemoteKey());
            botActive.put(oldBot(), true);
            botActive.put(freshBot(), true);
            botMemberships.put(oldBot(), ConcurrentHashMap.newKeySet());
            botMemberships.get(oldBot()).add("old-channel");
            botMemberships.put(freshBot(), ConcurrentHashMap.newKeySet());
            botMemberships.get(freshBot()).add("fresh-channel");
            adapter = switch (kind) {
                case GITHUB -> github();
                case GITLAB -> gitlab();
                case SLACK -> slack();
                case OUTLINE -> outline();
            };
        }

        String oldRemoteKey() {
            return kind == IntegrationKind.SLACK ? "old-token-" + oldId : Long.toString(oldId);
        }

        String freshRemoteKey() {
            return kind == IntegrationKind.SLACK ? "fresh-token-" + oldId : Long.toString(freshId);
        }

        String oldBot() {
            return "U" + oldId;
        }

        String freshBot() {
            return "U" + freshId;
        }

        String team() {
            return "T" + oldId;
        }

        String origin() {
            return switch (kind) {
                case GITHUB -> "https://github.com";
                case GITLAB -> "https://gitlab.example.test";
                case SLACK -> "https://slack.com";
                case OUTLINE -> "https://outline.example.test";
            };
        }

        String instanceKey(boolean fresh) {
            return kind == IntegrationKind.SLACK ? team() : Long.toString(fresh ? freshId : oldId);
        }

        String hookUrl(boolean fresh) {
            return URLS.originUri(
                            OutlineWebhookController.PATH_PREFIX + "{keyId}/{routeId}",
                            "key",
                            fresh ? freshRoute : oldRoute)
                    .toString();
        }

        CredentialBundle credential(boolean fresh) {
            return new BearerToken(fresh ? freshRemoteKey() : oldRemoteKey(), null);
        }

        ConnectionConfig config(boolean fresh, boolean sameBot) {
            long id = fresh ? freshId : oldId;
            return switch (kind) {
                case GITHUB -> new ConnectionConfig.GitHubAppConfig(id, 77L, "org", origin(), Set.of());
                case GITLAB ->
                    new ConnectionConfig.GitLabConfig(origin(), 99L, id, Set.of(), null)
                            .withWebhookOwnership(
                                    id,
                                    ProviderResourceAdapter.fingerprint(hookUrl(fresh)),
                                    fresh ? freshRoute : oldRoute);
                case SLACK ->
                    new ConnectionConfig.SlackConfig(
                            team(),
                            fresh && !sameBot ? freshBot() : oldBot(),
                            ProviderResourceAdapter.slackTokenFingerprint(fresh ? freshRemoteKey() : oldRemoteKey()),
                            "Team",
                            null,
                            Set.of());
                case OUTLINE ->
                    new ConnectionConfig.OutlineConfig(
                            origin(), Long.toString(id), "key/" + (fresh ? freshRoute : oldRoute), Set.of());
            };
        }

        void completeAccepted() {
            for (String key : accepted) live.remove(key);
            if (kind == IntegrationKind.SLACK) {
                Objects.requireNonNull(botMemberships.get(oldBot())).clear();
                botActive.put(oldBot(), false);
            }
        }

        private void accept(String key) {
            accepted.add(key);
            if (crashAfterAccept) {
                crashAfterAccept = false;
                throw new SimulatedCrash();
            }
            if (terminal) {
                live.remove(key);
                return;
            }
            throw new IllegalStateException("accepted remotely; local timeout");
        }

        private void inspect() {
            if (crashBeforeRead) {
                crashBeforeRead = false;
                throw new SimulatedCrash();
            }
            if (readUnavailable) throw hidden404();
        }

        private static WebClientResponseException hidden404() {
            return WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
        }

        private ProviderResourceAdapter github() {
            var app = mock(GitHubAppTokenService.class);
            when(app.isConfigured()).thenReturn(true);
            when(app.getConfiguredAppId()).thenReturn(77L);
            when(app.installation(anyLong())).thenAnswer(call -> {
                inspect();
                if (!live.contains(call.getArgument(0, Long.class).toString()))
                    throw new InstallationNotFoundException(call.getArgument(0, Long.class));
                return mock(GitHubAppTokenService.AppInstallation.class);
            });
            doAnswer(call -> {
                        accept(call.getArgument(0, Long.class).toString());
                        return null;
                    })
                    .when(app)
                    .deleteInstallation(anyLong());
            return new GitHubResourceAdapter(app);
        }

        private ProviderResourceAdapter gitlab() {
            var client = mock(GitLabWebhookClient.class);
            when(client.listGroupWebhooksWithCredentials(anyString(), anyString(), anyLong()))
                    .thenAnswer(call -> {
                        inspect();
                        return live.stream()
                                .map(id -> new WebhookInfo(Long.parseLong(id), hookUrl(id.equals(freshRemoteKey()))))
                                .toList();
                    });
            when(client.deregisterGroupWebhookWithCredentials(anyString(), anyString(), anyLong(), anyLong()))
                    .thenAnswer(call -> {
                        accept(call.getArgument(3, Long.class).toString());
                        return terminal;
                    });
            var factory = new StaticListableBeanFactory();
            factory.addBean("client", client);
            return new GitLabResourceAdapter(factory.getBeanProvider(GitLabWebhookClient.class), URLS);
        }

        private ProviderResourceAdapter outline() {
            var client = mock(OutlineWebhookClient.class);
            when(client.listWebhookSubscriptions(anyString(), anyString())).thenAnswer(call -> {
                inspect();
                return live.stream()
                        .map(id -> new OutlineWebhookSubscription().id(id).url(hookUrl(id.equals(freshRemoteKey()))))
                        .toList();
            });
            when(client.deleteWebhookSubscription(anyString(), anyString(), anyString()))
                    .thenAnswer(call -> {
                        if (deleteUnavailable)
                            throw new IllegalStateException("connection lost before provider acknowledgement");
                        accept(call.getArgument(2));
                        return terminal;
                    });
            return new OutlineResourceAdapter(
                    new StaticListableBeanFactory(Map.of("outline", client))
                            .getBeanProvider(OutlineWebhookClient.class),
                    URLS);
        }

        private ProviderResourceAdapter slack() {
            var client = mock(SlackOAuthClient.class);
            when(client.botIdentity(anyString())).thenAnswer(call -> {
                inspect();
                String token = call.getArgument(0);
                if (!live.contains(token)) throw new SlackOAuthException("token_revoked");
                return new SlackOAuthClient.BotIdentity(
                        true, team(), token.equals(freshRemoteKey()) ? freshBot() : oldBot(), null);
            });
            doAnswer(call -> {
                        accept(call.getArgument(0));
                        return null;
                    })
                    .when(client)
                    .revokeStrict(anyString());
            return new SlackResourceAdapter(
                    new StaticListableBeanFactory(Map.of("slack", client)).getBeanProvider(SlackOAuthClient.class));
        }
    }

    private static final class SimulatedCrash extends Error {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-13T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
