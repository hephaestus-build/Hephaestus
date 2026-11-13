package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ConnectionProviderRepairRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairDispatcher;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class GitLabSuspendedWebhookCleanupIntegrationTest extends BaseIntegrationTest {
    private static final AtomicLong HOOK_IDS = new AtomicLong(10_000_000);
    private final long firstId = HOOK_IDS.incrementAndGet();
    private final long secondId = HOOK_IDS.incrementAndGet();

    @Autowired
    private PublicApiUrls urls;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private ConnectionService lifecycle;

    @Autowired
    private GitLabWebhookService webhooks;

    @Autowired
    private GitLabSuspendedWebhookCleanup cleanup;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProviderResourceRepairDispatcher dispatcher;

    @Autowired
    private ConnectionProviderRepairRepository references;

    @Autowired
    private GitLabWebhookClient provider;

    private final ConcurrentHashMap<Long, String> upstreamHooks = new ConcurrentHashMap<>();
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final ConcurrentHashMap<Long, AtomicInteger> deletions = new ConcurrentHashMap<>();

    @BeforeEach
    void providerRemovesOnlyOwnedHooks() {
        reset(provider);
        when(provider.listGroupWebhooksWithCredentials(anyString(), anyString(), anyLong()))
                .thenAnswer(call -> {
                    if (unavailable.get()) throw new IllegalStateException("provider echoed cleanup-test-token");
                    return upstreamHooks.entrySet().stream()
                            .map(hook -> new WebhookInfo(hook.getKey(), hook.getValue()))
                            .toList();
                });
        when(provider.deregisterGroupWebhookWithCredentials(anyString(), anyString(), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    Long id = call.getArgument(3);
                    upstreamHooks.remove(id);
                    deletions
                            .computeIfAbsent(id, ignored -> new AtomicInteger())
                            .incrementAndGet();
                    return true;
                });
    }

    @Test
    void shouldRepairALostDeactivationAndKeepAnotherWorkspacesActiveHook() {
        Connection suspended = connection(IntegrationState.SUSPENDED, firstId);
        Connection active = connection(IntegrationState.ACTIVE, secondId);

        reconcile();
        reconcile();

        assertThat(upstreamHooks.keySet()).containsExactly(secondId);
        assertThat(config(suspended).gitlabWebhookId()).isNull();
        assertThat(config(active).gitlabWebhookId()).isEqualTo(secondId);
        assertThat(deletions.get(firstId)).hasValue(1);
        assertThat(lifecycle.findBearerToken(workspace(suspended), suspended.getId()))
                .isPresent();
    }

    @Test
    void shouldRepairARegistrationThatFinishesAfterSuspensionWithoutARecordedHookId() {
        Connection connection = connection(IntegrationState.ACTIVE, null);
        when(provider.listGroupWebhooks(workspace(connection), 42L))
                .thenAnswer(call -> upstreamHooks.entrySet().stream()
                        .map(hook -> new WebhookInfo(hook.getKey(), hook.getValue()))
                        .toList());
        when(provider.registerGroupWebhook(eq(workspace(connection)), eq(42L), any(WebhookConfig.class)))
                .thenAnswer(call -> {
                    state(connection, IntegrationState.SUSPENDED);
                    reconcile();
                    WebhookConfig created = call.getArgument(2);
                    upstreamHooks.put(firstId, created.url());
                    return new WebhookInfo(firstId, created.url());
                });

        assertThat(webhooks.registerWebhook(
                                workspaces.findById(workspace(connection)).orElseThrow())
                        .registered())
                .isFalse();
        assertThat(config(connection).gitlabWebhookId()).isNull();
        reconcile();

        assertThat(upstreamHooks).isEmpty();
        assertThat(current(connection).getState()).isEqualTo(IntegrationState.SUSPENDED);
    }

    @Test
    void shouldRegisterAFreshHookAfterResumeAndKeepItWhenOldCleanupIsRedelivered() {
        Connection connection = connection(IntegrationState.SUSPENDED, firstId);
        cleanup.remove(workspace(connection), connection.getId());
        state(connection, IntegrationState.ACTIVE);
        when(provider.listGroupWebhooks(workspace(connection), 42L))
                .thenAnswer(call -> upstreamHooks.entrySet().stream()
                        .map(hook -> new WebhookInfo(hook.getKey(), hook.getValue()))
                        .toList());
        when(provider.registerGroupWebhook(eq(workspace(connection)), eq(42L), any(WebhookConfig.class)))
                .thenAnswer(call -> {
                    WebhookConfig created = call.getArgument(2);
                    upstreamHooks.put(secondId, created.url());
                    return new WebhookInfo(secondId, created.url());
                });

        assertThat(webhooks.registerWebhook(
                                workspaces.findById(workspace(connection)).orElseThrow())
                        .registered())
                .isTrue();
        cleanup.remove(workspace(connection), connection.getId());
        reconcile();

        assertThat(config(connection).gitlabWebhookId()).isEqualTo(secondId);
        assertThat(upstreamHooks.keySet()).containsExactly(secondId);
    }

    @Test
    void shouldRetainTheStoredIdWhenTheProviderRejectsTheCredential() {
        Connection connection = connection(IntegrationState.SUSPENDED, firstId);
        when(provider.listGroupWebhooksWithCredentials(anyString(), anyString(), anyLong()))
                .thenThrow(
                        WebClientResponseException.create(401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null));

        reconcile();

        assertThat(config(connection).gitlabWebhookId()).isEqualTo(firstId);
        assertThat(upstreamHooks).containsKey(firstId);
    }

    @Test
    void shouldRetainTheHookUntilADeletionFailureIsReconciled() {
        Connection suspended = connection(IntegrationState.SUSPENDED, firstId);
        unavailable.set(true);

        cleanup.remove(workspace(suspended), suspended.getId());
        reconcile();
        assertThat(config(suspended).gitlabWebhookId()).isEqualTo(firstId);
        assertThat(upstreamHooks.keySet()).containsExactly(firstId);

        unavailable.set(false);
        reconcile();

        assertThat(upstreamHooks).isEmpty();
        assertThat(config(suspended).gitlabWebhookId()).isNull();
    }

    @Test
    void shouldSkipCleanupThatArrivesAfterReactivation() {
        Connection resumed = connection(IntegrationState.SUSPENDED, firstId);
        state(resumed, IntegrationState.ACTIVE);

        cleanup.remove(workspace(resumed), resumed.getId());

        assertThat(upstreamHooks.keySet()).containsExactly(firstId);
        assertThat(config(resumed).gitlabWebhookId()).isEqualTo(firstId);
    }

    @Test
    void shouldRereadStateAfterWaitingForAConcurrentReactivation() throws Exception {
        Connection resumed = connection(IntegrationState.SUSPENDED, firstId);
        HeldTransaction activation =
                HeldTransaction.holdIn(transactions, jdbc, () -> state(resumed, IntegrationState.ACTIVE));
        var removing = CompletableFuture.runAsync(() -> cleanup.remove(workspace(resumed), resumed.getId()));
        activation.commitAfter(removing);
        removing.get(20, TimeUnit.SECONDS);

        assertThat(upstreamHooks.keySet()).containsExactly(firstId);
        assertThat(config(resumed).gitlabWebhookId()).isEqualTo(firstId);
    }

    @Test
    void shouldCaptureCleanupBeforeAReactivationCanRegisterAFreshHook() throws Exception {
        Connection resumed = connection(IntegrationState.SUSPENDED, firstId);
        HeldTransaction removing =
                HeldTransaction.holdIn(transactions, jdbc, () -> cleanup.remove(workspace(resumed), resumed.getId()));
        var activation = CompletableFuture.runAsync(() -> state(resumed, IntegrationState.ACTIVE));
        removing.commitAfter(activation);
        activation.get(20, TimeUnit.SECONDS);

        reconcile();
        assertThat(upstreamHooks).isEmpty();
        // This fixture writes state directly. Production activation detaches this old metadata.
        assertThat(current(resumed).getState()).isEqualTo(IntegrationState.ACTIVE);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldRetainDurableIntentWhenTheDeactivationWakeIsLost(CapturedOutput output) {
        Connection suspended = connection(IntegrationState.ACTIVE, firstId);
        unavailable.set(true);
        lifecycle.transition(
                suspended,
                new TransitionRequest(IntegrationState.SUSPENDED, "CREDENTIAL_REVOKED", "SYSTEM", null, null, null));
        reconcile();
        assertThat(config(suspended).gitlabWebhookId()).isEqualTo(firstId);
        assertThat(references.findByWorkspaceIdAndConnection_Id(workspace(suspended), suspended.getId()))
                .isNotEmpty();
        // Even deleting the wake cannot delete the durable target or prevent the next sweep.
        jdbc.update("DELETE FROM event_publication WHERE serialized_event LIKE ?", eventPattern(suspended));
        unavailable.set(false);
        reconcile();
        assertThat(upstreamHooks).isEmpty();
        assertThat(config(suspended).gitlabWebhookId()).isNull();
        assertThat(output).doesNotContain("cleanup-test-token");
    }

    private Connection connection(IntegrationState state, @Nullable Long hookId) {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("hook-cleanup-" + UUID.randomUUID()));
        var connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "https://gitlab.example",
                new GitLabConfig("https://gitlab.example", 42L, hookId, Set.of(), null));
        connection.setState(state);
        connection.setCredentials(new BearerToken("cleanup-test-token", null), converter);
        Connection saved = connections.save(connection);
        if (hookId != null) upstreamHooks.put(hookId, hookUrl(saved));
        return saved;
    }

    private void reconcile() {
        releaseScheduledLeases();
        cleanup.reconcile();
        jdbc.update("UPDATE provider_resource_repair SET next_attempt_at = TIMESTAMP '1970-01-01' WHERE id IN "
                + "(SELECT repair_id FROM connection_provider_repair r JOIN connection c ON c.id = r.connection_id "
                + "WHERE c.kind = 'GITLAB' AND c.config ->> 'serverUrl' = 'https://gitlab.example')");
        jdbc.update("UPDATE shedlock SET lock_until = TIMESTAMP '1970-01-01' WHERE name = 'provider-resource-repair'");
        dispatcher.reconcile();
    }

    @AfterEach
    void releaseScheduledLeases() {
        jdbc.update(
                "UPDATE shedlock SET lock_until = TIMESTAMP '1970-01-01 00:00:00' WHERE name = ?",
                GitLabSuspendedWebhookCleanup.LOCK_NAME);
    }

    @AfterEach
    void resetProvider() {
        reset(provider);
    }

    private String hookUrl(Connection connection) {
        return urls.originUri("/webhooks/gitlab/connections/{id}/old-key/old-route", connection.getId())
                .toString();
    }

    private void state(Connection connection, IntegrationState next) {
        transactions.executeWithoutResult(status -> {
            connections.acquireLifecycleLock(connection.getId(), workspace(connection));
            Connection current = current(connection);
            current.setState(next);
            connections.saveAndFlush(current);
        });
    }

    private static long workspace(Connection connection) {
        return connection.getWorkspace().getId();
    }

    private Connection current(Connection connection) {
        return connections
                .findByIdAndWorkspaceId(connection.getId(), workspace(connection))
                .orElseThrow();
    }

    private GitLabConfig config(Connection connection) {
        return (GitLabConfig) current(connection).getConfig();
    }

    private static String eventPattern(Connection connection) {
        return "%\"connectionId\":" + connection.getId() + ",%";
    }
}
