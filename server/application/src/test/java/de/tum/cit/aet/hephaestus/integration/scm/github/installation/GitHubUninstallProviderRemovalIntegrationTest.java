package de.tum.cit.aet.hephaestus.integration.scm.github.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubInstallationLifecycle;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A GitHub App uninstall removes the workspace's Outline subscription at Outline before its purge erases the Outline
 * token. The installation events arrive through the real handler; GitHub and Outline are fixtures.
 */
// The same context as OutlineDocumentSyncIntegrationTest: Outline is off by default, and a removal skips a
// connection whose integration is off.
@TestPropertySource(properties = "hephaestus.integration.outline.enabled=true")
class GitHubUninstallProviderRemovalIntegrationTest extends BaseIntegrationTest {
    private static final String OUTLINE = "https://outline.example.test";

    @Autowired
    private IdentityProviderLookup providerLookup;

    @Autowired
    private GitHubInstallationLifecycle installationLifecycle;

    @Autowired
    private IdentityProviderRepository identityProviders;

    @Autowired
    private ProvisioningListener provisioningListener;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private EventPayloadDeserializer deserializer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private SyncJobRepository syncJobs;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private PublicApiUrls urls;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    /** The shared test double of the base context. */
    @Autowired
    private OutlineWebhookClient outlineWebhookClient;

    private final GitHubAppTokenService appTokens = mock(GitHubAppTokenService.class);

    /** Outline's subscriptions, from id to URL. */
    private final ConcurrentHashMap<String, String> outline = new ConcurrentHashMap<>();

    private GitHubInstallationMessageHandler installations;

    @BeforeEach
    void setUp() {
        if (identityProviders
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .isEmpty()) {
            identityProviders.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com"));
        }
        installations = new GitHubInstallationMessageHandler(
                provisioningListener,
                organizationService,
                appTokens,
                providerLookup,
                installationLifecycle,
                deserializer,
                transactionTemplate);
        when(outlineWebhookClient.listWebhookSubscriptions(anyString(), anyString()))
                .thenAnswer(call -> outline.entrySet().stream()
                        .map(subscription -> new OutlineWebhookSubscription()
                                .id(subscription.getKey())
                                .url(subscription.getValue()))
                        .toList());
        when(outlineWebhookClient.deleteWebhookSubscription(anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    if (TransactionSynchronizationManager.isActualTransactionActive())
                        throw new IllegalStateException("A provider call ran inside a transaction");
                    return outline.remove(call.<String>getArgument(2)) != null;
                });
    }

    @AfterEach
    void forgetOutline() {
        reset(outlineWebhookClient);
    }

    @Test
    void shouldRemoveTheOutlineSubscriptionBeforeThePurgeWhenGitHubReportsTheUninstall() throws Exception {
        long installationId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) + 1;
        when(appTokens.isInstallationSuspended(installationId)).thenReturn(false);
        installations.handleEvent(installationEvent("installation.created", installationId));
        Workspace workspace =
                workspaceRepository.findByInstallationId(installationId).orElseThrow();
        String subscription = "subscription-" + UUID.randomUUID();
        Connection connection = outlineConnection(workspace, subscription);

        installations.handleEvent(installationEvent("installation.deleted", installationId));

        // The removal that the disconnect's listener claimed first can still be finishing its call.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(outline)
                        .as("the subscription is gone at Outline")
                        .doesNotContainKey(subscription));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(openRemovals(connection))
                        .as("no removal waits for an instance admin")
                        .isEmpty());
        assertThat(jdbc.queryForObject(
                        "SELECT credentials_encrypted IS NOT NULL FROM connection WHERE workspace_id = ? AND id = ?",
                        Boolean.class,
                        workspace.getId(),
                        connection.getId()))
                .as("the purge releases the Outline token")
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, workspace.getId()))
                .isEqualTo("PURGED");
    }

    @Test
    void shouldEraseNothingWhileAPurgeGuardRefusesAndFinishWhenGitHubDeliversAgain() throws Exception {
        long installationId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) + 1;
        when(appTokens.isInstallationSuspended(installationId)).thenReturn(false);
        installations.handleEvent(installationEvent("installation.created", installationId));
        Workspace workspace =
                workspaceRepository.findByInstallationId(installationId).orElseThrow();
        String subscription = "subscription-" + UUID.randomUUID();
        Connection connection = outlineConnection(workspace, subscription);
        SyncJob sync = new SyncJob(
                workspace,
                connection,
                IntegrationKind.OUTLINE,
                SyncJobType.RECONCILIATION,
                SyncJobTrigger.MANUAL,
                null);
        sync.setStatus(SyncJobStatus.RUNNING);
        syncJobs.saveAndFlush(sync);
        var deleted = installationEvent("installation.deleted", installationId);

        assertThatThrownBy(() -> installations.handleEvent(deleted)).isInstanceOf(WorkspacePurgeBlockedException.class);

        assertThat(githubState(workspace))
                .as("the uninstall erases nothing while a guard refuses the purge")
                .isEqualTo("ACTIVE");
        assertThat(outline).containsKey(subscription);
        assertThat(jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, workspace.getId()))
                .isEqualTo("ACTIVE");

        jdbc.update("DELETE FROM sync_job WHERE id = ?", sync.getId());
        installations.handleEvent(deleted);

        assertThat(githubState(workspace)).isEqualTo("UNINSTALLED");
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(outline).doesNotContainKey(subscription));
        assertThat(jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, workspace.getId()))
                .isEqualTo("PURGED");
    }

    private @Nullable String githubState(Workspace workspace) {
        return jdbc.queryForObject(
                "SELECT state FROM connection WHERE workspace_id = ? AND kind = 'GITHUB'",
                String.class,
                workspace.getId());
    }

    private Connection outlineConnection(Workspace workspace, String subscription) {
        String key = "key-" + UUID.randomUUID().toString().substring(0, 8);
        String route = "route-" + UUID.randomUUID().toString().substring(0, 8);
        outline.put(
                subscription,
                urls.originUri(OutlineWebhookController.PATH_PREFIX + "{keyId}/{routeId}", key, route)
                        .toString());
        var config = new ConnectionConfig.OutlineConfig(OUTLINE, subscription, key + "/" + route, Set.of());
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Connection connection = new Connection(workspace, IntegrationKind.OUTLINE, OUTLINE, config);
            connection.setCredentials(new BearerToken("outline-token", null), converter);
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

    private GitHubInstallationEventDTO installationEvent(String name, long installationId) throws Exception {
        var event = (ObjectNode) objectMapper.readTree(
                new ClassPathResource("github/" + name + ".json").getContentAsString(StandardCharsets.UTF_8));
        var installation = (ObjectNode) event.get("installation");
        installation.put("id", installationId);
        // Its own account, so no workspace of an earlier test claims the installation.
        ((ObjectNode) installation.get("account"))
                .put("id", installationId)
                .put("login", "uninstall-" + installationId);
        return objectMapper.treeToValue(event, GitHubInstallationEventDTO.class);
    }
}
