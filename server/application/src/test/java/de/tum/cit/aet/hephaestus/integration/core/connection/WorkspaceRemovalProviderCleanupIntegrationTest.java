package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderRemoval;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The owner's workspace removal removes the GitLab webhooks it registered, with GitLab's hook API faked in memory. */
class WorkspaceRemovalProviderCleanupIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final String ORIGIN = "https://gitlab.example.test";
    private static final String MARKER = "marker";
    private static final String URL =
            "https://receiver.example.test/webhooks/gitlab/connections/1/key/route?hephaestus_resource=" + MARKER;
    private static final String HASH = ProviderResourceAdapter.fingerprint(URL);

    /** The shared test double of the base context: no new context for this class. */
    @Autowired
    private GitLabWebhookClient gitLabWebhookClient;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceProviderRemoval providerRemoval;

    /** GitLab's hooks by project, each a map from hook id to URL. */
    private final Map<Long, Map<Long, String>> gitlab = new ConcurrentHashMap<>();

    @AfterEach
    void forgetGitLab() {
        reset(gitLabWebhookClient);
    }

    @BeforeEach
    void fakeGitLab() {
        when(gitLabWebhookClient.listProjectWebhooksWithCredentials(anyString(), anyString(), anyLong()))
                .thenAnswer(call -> gitlab.getOrDefault((long) call.getArgument(2), Map.of()).entrySet().stream()
                        .map(hook -> new WebhookInfo(hook.getKey(), hook.getValue()))
                        .toList());
        when(gitLabWebhookClient.deregisterProjectWebhookWithCredentials(
                        anyString(), anyString(), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    Map<Long, String> hooks = gitlab.get((long) call.getArgument(2));
                    return hooks != null && hooks.remove((long) call.getArgument(3)) != null;
                });
    }

    @Test
    void shouldRemoveEveryProjectHookAtGitLabWhenTheOwnerRemovesTheWorkspace() {
        Workspace workspace = workspace();
        long first = projectId();
        long second = projectId();
        Connection connection = gitLabConnection(workspace, Map.of(first, 11L, second, 12L));

        lifecycle.purgeWorkspace(WorkspaceContext.fromWorkspace(workspace, Set.of(), null));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(gitlab.get(first)).isEmpty());
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(gitlab.get(second)).isEmpty());
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(openRemovals(connection)).isEmpty());
        assertThat(credentialKept(connection))
                .as("the purge releases the credential")
                .isFalse();
        assertThat(state(workspace)).isEqualTo("PURGED");
    }

    @Test
    void shouldRecordEachHookThatGitLabDidNotConfirmRemovedAndStillPurge() {
        when(gitLabWebhookClient.listProjectWebhooksWithCredentials(anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("GitLab is unreachable"));
        Workspace workspace = workspace();
        long project = projectId();
        Connection connection = gitLabConnection(workspace, Map.of(project, 21L));

        lifecycle.purgeWorkspace(WorkspaceContext.fromWorkspace(workspace, Set.of(), null));

        assertThat(gitlab.get(project)).as("the hook is still at GitLab").containsKey(21L);
        // The removal that a listener claimed first can still be finishing its call.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(openRemovals(connection))
                        .as("its removal stays recorded for an instance admin, never dropped silently")
                        .containsExactly("AWAITING_CONFIRMATION"));
        assertThat(credentialKept(connection)).isFalse();
        assertThat(state(workspace)).isEqualTo("PURGED");
    }

    @Test
    void shouldDisconnectAgainAConnectionThatWasReconnectedAfterARemovalThatDidNotFinish() {
        Workspace workspace = workspace();
        Connection connection = gitLabConnection(workspace, Map.of(projectId(), 31L));
        // A removal disconnected the connection, then its purge did not run, and an admin reconnected it.
        providerRemoval.removeBeforePurge(workspace.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Connection reconnected = connections
                    .findByIdAndWorkspaceId(Objects.requireNonNull(connection.getId()), workspace.getId())
                    .orElseThrow();
            ConnectionFixtures.reachState(reconnected, IntegrationState.ACTIVE);
            reconnected.setCredentials(new BearerToken("reconnected-token", null), converter);
            connections.saveAndFlush(reconnected);
        });

        lifecycle.purgeWorkspace(WorkspaceContext.fromWorkspace(workspace, Set.of(), null));

        assertThat(state(workspace)).isEqualTo("PURGED");
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM connection WHERE workspace_id = ? AND id = ?",
                        String.class,
                        workspace.getId(),
                        connection.getId()))
                .isEqualTo("UNINSTALLED");
        assertThat(credentialKept(connection))
                .as("a purged workspace keeps no credential")
                .isFalse();
    }

    private Workspace workspace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var owner = persistUser("removal-owner-" + suffix);
        return createWorkspace(
                "removal-hooks-" + suffix, "Removal hooks", "removal-org-" + suffix, AccountType.ORG, owner);
    }

    private static long projectId() {
        return Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) + 1;
    }

    private Connection gitLabConnection(Workspace workspace, Map<Long, Long> hooks) {
        long groupId = projectId();
        var config = new ConnectionConfig.GitLabConfig(ORIGIN, groupId, null, Set.of(), null);
        for (var hook : hooks.entrySet()) {
            config = config.withProjectHook(hook.getKey(), hook.getValue(), HASH, MARKER);
            gitlab.computeIfAbsent(hook.getKey(), ignored -> new ConcurrentHashMap<>())
                    .put(hook.getValue(), URL);
        }
        var stored = config;
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            Connection connection = new Connection(workspace, IntegrationKind.GITLAB, Long.toString(groupId), stored);
            connection.setCredentials(new BearerToken("removal-token", null), converter);
            ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
            return connections.saveAndFlush(connection);
        }));
    }

    private List<@Nullable String> openRemovals(Connection connection) {
        return jdbc.queryForList(
                "SELECT r.state FROM provider_resource_repair r JOIN connection_provider_repair c ON c.repair_id = r.id"
                        + " WHERE c.workspace_id = ? AND c.connection_id = ?",
                String.class,
                connection.getWorkspace().getId(),
                connection.getId());
    }

    private boolean credentialKept(Connection connection) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT credentials_encrypted IS NOT NULL FROM connection WHERE workspace_id = ? AND id = ?",
                Boolean.class,
                connection.getWorkspace().getId(),
                connection.getId()));
    }

    private String state(Workspace workspace) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, workspace.getId()));
    }
}
