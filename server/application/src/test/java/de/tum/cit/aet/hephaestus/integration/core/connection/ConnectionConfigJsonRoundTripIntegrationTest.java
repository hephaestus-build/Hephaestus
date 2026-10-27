package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persist + flush + clear + re-fetch each {@link ConnectionConfig} sealed subtype and
 * assert the JSONB column round-trips identically (subtype, fields, discriminator).
 * Also pins the {@code @PrePersist} kind/config compatibility guard.
 */
@Transactional
class ConnectionConfigJsonRoundTripIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("conn-roundtrip"));
    }

    @Test
    void gitHubAppConfig_roundTrips() {
        ConnectionConfig.GitHubAppConfig original = new ConnectionConfig.GitHubAppConfig(
                42L, "acme-org", "https://ghes.example.com", Set.of("issues", "pulls"));
        Long id = persistAndClear(IntegrationKind.GITHUB, "installation-42", original);

        Connection reloaded = connectionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConfig()).isInstanceOf(ConnectionConfig.GitHubAppConfig.class);
        ConnectionConfig.GitHubAppConfig cfg = (ConnectionConfig.GitHubAppConfig) reloaded.getConfig();
        assertThat(cfg.installationId()).isEqualTo(42L);
        assertThat(cfg.orgLogin()).isEqualTo("acme-org");
        assertThat(cfg.serverUrl()).isEqualTo("https://ghes.example.com");
        assertThat(cfg.enabledStreams()).containsExactlyInAnyOrder("issues", "pulls");

        assertDiscriminator(id, "GITHUB_APP");
    }

    @Test
    void gitHubPatConfig_roundTrips() {
        ConnectionConfig.GitHubPatConfig original =
                new ConnectionConfig.GitHubPatConfig("acme-org", null, Set.of("repos"));
        Long id = persistAndClear(IntegrationKind.GITHUB, "pat", original);

        Connection reloaded = connectionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConfig()).isInstanceOf(ConnectionConfig.GitHubPatConfig.class);
        ConnectionConfig.GitHubPatConfig cfg = (ConnectionConfig.GitHubPatConfig) reloaded.getConfig();
        assertThat(cfg.orgLogin()).isEqualTo("acme-org");
        assertThat(cfg.serverUrl()).isNull();
        assertThat(cfg.enabledStreams()).containsExactly("repos");

        assertDiscriminator(id, "GITHUB_PAT");
    }

    @Test
    void gitLabConfig_plaintext_roundTrips() {
        ConnectionConfig.GitLabConfig original = new ConnectionConfig.GitLabConfig(
                "https://gitlab.example.com",
                1234L,
                5678L,
                ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                Set.of("merge_requests"),
                null);
        Long id = persistAndClear(IntegrationKind.GITLAB, "https://gitlab.example.com", original);

        Connection reloaded = connectionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConfig()).isInstanceOf(ConnectionConfig.GitLabConfig.class);
        ConnectionConfig.GitLabConfig cfg = (ConnectionConfig.GitLabConfig) reloaded.getConfig();
        assertThat(cfg.serverUrl()).isEqualTo("https://gitlab.example.com");
        assertThat(cfg.gitlabGroupId()).isEqualTo(1234L);
        assertThat(cfg.gitlabWebhookId()).isEqualTo(5678L);
        assertThat(cfg.signingMode()).isEqualTo(ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT);
        assertThat(cfg.enabledStreams()).containsExactly("merge_requests");

        assertDiscriminator(id, "GITLAB");
    }

    @Test
    void gitLabConfig_whsec_roundTrips() {
        ConnectionConfig.GitLabConfig original = new ConnectionConfig.GitLabConfig(
                "https://gitlab.example.com",
                null,
                null,
                ConnectionConfig.GitLabConfig.SigningMode.WHSEC,
                Set.of(),
                null);
        Long id = persistAndClear(IntegrationKind.GITLAB, "https://gitlab.example.com/whsec", original);

        Connection reloaded = connectionRepository.findById(id).orElseThrow();
        ConnectionConfig.GitLabConfig cfg = (ConnectionConfig.GitLabConfig) reloaded.getConfig();
        assertThat(cfg.signingMode()).isEqualTo(ConnectionConfig.GitLabConfig.SigningMode.WHSEC);
    }

    @Test
    void shouldKeepReleasedGitLabProjectExclusionsThroughReadAndConfigUpdates() {
        Long id = persistAndClear(
                IntegrationKind.GITLAB,
                "https://gitlab.example.test",
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.example.test",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of("issues"),
                        null));
        entityManager
                .createNativeQuery("""
                UPDATE connection SET config = CAST(:config AS jsonb)
                WHERE id = :id AND workspace_id = :workspaceId
                """)
                .setParameter("config", """
                        {"type":"GITLAB","serverUrl":"https://gitlab.example.test",
                         "gitlabGroupId":42,"gitlabWebhookId":73,"signingMode":"PLAINTEXT",
                         "enabledStreams":["issues"],"tokenMetadata":null,
                         "projectExclusions":{"nativeIds":[7001,7002],"unresolvedPaths":["team/legacy"]}}
                        """)
                .setParameter("id", id)
                .setParameter("workspaceId", workspace.getId())
                .executeUpdate();
        entityManager.clear();

        Connection connection = connectionRepository.findById(id).orElseThrow();
        var config = (ConnectionConfig.GitLabConfig) connection.getConfig();
        var expected = new ConnectionConfig.GitLabProjectExclusions(Set.of(7001L, 7002L), Set.of("team/legacy"));
        assertThat(config.projectExclusions()).isEqualTo(expected);
        var metadata = new ConnectionConfig.GitLabTokenMetadata(
                LocalDate.of(2027, 1, 1), Instant.parse("2026-10-15T10:00:00Z"));
        connection.setConfig(
                config.withGitlabGroupId(43L).withGitlabWebhookId(74L).withTokenMetadata(metadata));
        connectionRepository.saveAndFlush(connection);
        entityManager.clear();

        var reloaded = (ConnectionConfig.GitLabConfig)
                connectionRepository.findById(id).orElseThrow().getConfig();
        assertThat(reloaded.projectExclusions()).isEqualTo(expected);
        assertThat(reloaded.gitlabGroupId()).isEqualTo(43L);
        assertThat(reloaded.gitlabWebhookId()).isEqualTo(74L);
        assertThat(reloaded.tokenMetadata()).isEqualTo(metadata);
        Object stored = entityManager
                .createNativeQuery("""
                SELECT (config -> 'projectExclusions') @> CAST(:expected AS jsonb)
                       AND CAST(:expected AS jsonb) @> (config -> 'projectExclusions')
                FROM connection WHERE id = :id AND workspace_id = :workspaceId
                """)
                .setParameter(
                        "expected",
                        "{\"nativeIds\":[7001,7002],\"unresolvedPaths\":[\"team/legacy\"],\"stopReceipts\":{}}")
                .setParameter("id", id)
                .setParameter("workspaceId", workspace.getId())
                .getSingleResult();
        assertThat(stored).isEqualTo(true);
    }

    @Test
    void shouldKeepGitLabStopReceiptsThroughReadAndConfigUpdates() {
        UUID receipt = UUID.fromString("6f1c2a3b-4d5e-4f60-8a71-92b3c4d5e6f7");
        var exclusions = ConnectionConfig.GitLabProjectExclusions.empty()
                .stop(7001L, "team/stopped", receipt)
                .exclude(null, "team/legacy");
        Long id = persistAndClear(
                IntegrationKind.GITLAB,
                "https://gitlab.example.test",
                new ConnectionConfig.GitLabConfig(
                                "https://gitlab.example.test",
                                null,
                                null,
                                ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                                Set.of("issues"),
                                null)
                        .withProjectExclusions(exclusions));

        Connection connection = connectionRepository.findById(id).orElseThrow();
        var config = (ConnectionConfig.GitLabConfig) connection.getConfig();
        assertThat(config.projectExclusions()).isEqualTo(exclusions);
        assertThat(config.projectExclusions().stopReceipt(7001L)).contains(receipt);
        connection.setConfig(config.withGitlabWebhookId(74L));
        connectionRepository.saveAndFlush(connection);
        entityManager.clear();

        var reloaded = (ConnectionConfig.GitLabConfig)
                connectionRepository.findById(id).orElseThrow().getConfig();
        assertThat(reloaded.projectExclusions()).isEqualTo(exclusions);
    }

    @Test
    void slackConfig_roundTrips() {
        ConnectionConfig.SlackConfig original =
                new ConnectionConfig.SlackConfig("T123", "Acme Slack", /* retentionDays */ null, Set.of("messages"));
        Long id = persistAndClear(IntegrationKind.SLACK, "T123", original);

        Connection reloaded = connectionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConfig()).isInstanceOf(ConnectionConfig.SlackConfig.class);
        ConnectionConfig.SlackConfig cfg = (ConnectionConfig.SlackConfig) reloaded.getConfig();
        assertThat(cfg.teamId()).isEqualTo("T123");
        assertThat(cfg.teamName()).isEqualTo("Acme Slack");
        assertThat(cfg.enabledStreams()).containsExactly("messages");

        assertDiscriminator(id, "SLACK");
    }

    /** A stored Slack config may carry {@code notificationChannelId} and {@code teamLabel}, which are unmapped. */
    @Test
    void shouldReadASlackConfigWhenItCarriesUnmappedKeys() {
        Long id = persistAndClear(
                IntegrationKind.SLACK,
                "T456",
                new ConnectionConfig.SlackConfig("T456", "Legacy Slack", 14, Set.of("messages")));
        entityManager
                .createNativeQuery("UPDATE connection SET config = config || "
                        + "'{\"notificationChannelId\":\"C123\",\"teamLabel\":\"Platform\"}'::jsonb "
                        + "WHERE id = :id AND workspace_id = :wsId")
                .setParameter("id", id)
                .setParameter("wsId", workspace.getId())
                .executeUpdate();
        entityManager.clear();

        ConnectionConfig.SlackConfig cfg = (ConnectionConfig.SlackConfig)
                connectionRepository.findById(id).orElseThrow().getConfig();
        assertThat(cfg.teamId()).isEqualTo("T456");
        assertThat(cfg.retentionDays()).isEqualTo(14);
        assertThat(cfg.enabledStreams()).containsExactly("messages");
    }

    /** The webhook route survives the JSONB round-trip: the receiver derives the subscription's secret from it. */
    @Test
    void outlineConfig_roundTrips_includingTheWebhookRoute() {
        ConnectionConfig.OutlineConfig original = new ConnectionConfig.OutlineConfig(
                "https://outline.example.com", "sub-abc", "key/route", Set.of("documents"));
        Long id = persistAndClear(IntegrationKind.OUTLINE, "https://outline.example.com", original);

        Connection reloaded = connectionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConfig()).isInstanceOf(ConnectionConfig.OutlineConfig.class);
        ConnectionConfig.OutlineConfig cfg = (ConnectionConfig.OutlineConfig) reloaded.getConfig();
        assertThat(cfg.serverUrl()).isEqualTo("https://outline.example.com");
        assertThat(cfg.webhookSubscriptionId()).isEqualTo("sub-abc");
        assertThat(cfg.webhookRoute()).isEqualTo("key/route");
        assertThat(cfg.enabledStreams()).containsExactly("documents");

        assertDiscriminator(id, "OUTLINE");
    }

    /**
     * The Outline consumer resolves a delivery's subscription through the JSONB expression
     * index {@code ix_connection_outline_subscription}. Exercised against real Postgres because the
     * {@code ->>} operator, the quoted projection aliases, and the ACTIVE/OUTLINE predicates only
     * exist in native SQL — a mock cannot tell us the query parses, let alone that it isolates
     * tenants.
     */
    @Test
    void outlineSubscriptionLookup_isKeyedOnTheSubscriptionAndNeverCrossesWorkspaces() {
        Workspace other = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("conn-roundtrip-b"));

        persistActiveOutline(workspace, "sub-a", "key/route-a");
        persistActiveOutline(other, "sub-b", "key/route-b");
        entityManager.flush();
        entityManager.clear();

        var a = connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-a");
        assertThat(a).hasSize(1);
        assertThat(a.getFirst().getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(a.getFirst().getRoute()).isEqualTo("key/route-a");

        var b = connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-b");
        assertThat(b).hasSize(1);
        assertThat(b.getFirst().getWorkspaceId()).isEqualTo(other.getId());
        assertThat(b.getFirst().getRoute()).isEqualTo("key/route-b");

        // A forged id matches nothing.
        assertThat(connectionRepository.findOutlineSubscriptionsBySubscriptionId("forged"))
                .isEmpty();
    }

    /** A non-ACTIVE row is invisible to the webhook resolver: a suspended workspace stops verifying. */
    @Test
    void outlineSubscriptionLookup_ignoresNonActiveConnections() {
        Connection suspended = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "https://outline.example.com/suspended",
                new ConnectionConfig.OutlineConfig(
                        "https://outline.example.com", "sub-suspended", "secret-x", Set.of()));
        suspended.setState(IntegrationState.SUSPENDED);
        connectionRepository.save(suspended);
        entityManager.flush();
        entityManager.clear();

        assertThat(connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-suspended"))
                .isEmpty();
    }

    /**
     * The route projection and the heal read every ACTIVE Outline connection through this native query. A broken
     * filter or a renamed JSON key would refuse every live subscription within a minute, so it runs against real
     * Postgres.
     */
    @Test
    void activeOutlineRoutes_holdEveryActiveConnectionAndItsRoute() {
        Workspace unrouted = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("conn-routes-unrouted"));
        Workspace suspendedWorkspace =
                workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("conn-routes-suspended"));
        persistActiveOutline(workspace, "sub-routed", "key/route-routed");
        persistActiveOutline(unrouted, "sub-unrouted", null);
        Connection suspended = new Connection(
                suspendedWorkspace,
                IntegrationKind.OUTLINE,
                "https://outline.example.com/sub-suspended-route",
                new ConnectionConfig.OutlineConfig(
                        "https://outline.example.com", "sub-suspended-route", "key/route-suspended", Set.of()));
        suspended.setState(IntegrationState.SUSPENDED);
        connectionRepository.save(suspended);
        entityManager.flush();
        entityManager.clear();

        var mine = connectionRepository.findActiveOutlineRoutes().stream()
                .filter(row -> Set.of(workspace.getId(), unrouted.getId(), suspendedWorkspace.getId())
                        .contains(row.getWorkspaceId()))
                .toList();

        assertThat(mine)
                .extracting(
                        ConnectionRepository.OutlineRouteRow::getWorkspaceId,
                        ConnectionRepository.OutlineRouteRow::getRoute)
                .containsExactlyInAnyOrder(tuple(workspace.getId(), "key/route-routed"), tuple(unrouted.getId(), null));
    }

    private void persistActiveOutline(Workspace ws, String subscriptionId, @Nullable String route) {
        Connection conn = new Connection(
                ws,
                IntegrationKind.OUTLINE,
                "https://outline.example.com/" + subscriptionId,
                new ConnectionConfig.OutlineConfig("https://outline.example.com", subscriptionId, route, Set.of()));
        conn.setState(IntegrationState.ACTIVE);
        connectionRepository.save(conn);
    }

    @Test
    void prePersist_rejectsKindConfigMismatch() {
        ConnectionConfig.GitHubAppConfig wrong = new ConnectionConfig.GitHubAppConfig(1L, "acme", null, Set.of());
        Connection bad = new Connection(workspace, IntegrationKind.GITLAB, "instance-x", wrong);
        // Spring wraps the @PrePersist IllegalStateException in InvalidDataAccessApiUsageException.
        assertThatThrownBy(() -> {
                    connectionRepository.save(bad);
                    entityManager.flush();
                })
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GITLAB")
                .hasMessageContaining("GitHubAppConfig");
    }

    @Test
    void version_incrementsOnUpdate() {
        Connection conn = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "v-test",
                new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        Connection saved = connectionRepository.save(conn);
        entityManager.flush();
        Long v0 = saved.getVersion();

        saved.setDisplayName("changed");
        connectionRepository.save(saved);
        entityManager.flush();
        assertThat(saved.getVersion()).isGreaterThan(v0);
    }

    private Long persistAndClear(IntegrationKind kind, String instanceKey, ConnectionConfig config) {
        Connection conn = new Connection(workspace, kind, instanceKey, config);
        Connection saved = connectionRepository.save(conn);
        entityManager.flush();
        entityManager.clear();
        return saved.getId();
    }

    private void assertDiscriminator(Long id, String expectedType) {
        // workspace_id predicate satisfies the tenancy statement inspector on the
        // workspace-scoped `connection` table.
        String json = (String) entityManager
                .createNativeQuery("SELECT config::text FROM connection WHERE id = :id AND workspace_id = :wsId")
                .setParameter("id", id)
                .setParameter("wsId", workspace.getId())
                .getSingleResult();
        try {
            JsonNode tree = objectMapper.readTree(json);
            assertThat(tree.has("type"))
                    .as("JSONB column must carry 'type' discriminator")
                    .isTrue();
            assertThat(tree.get("type").asText()).isEqualTo(expectedType);
        } catch (Exception e) {
            throw new AssertionError("Failed parsing JSONB column for connection " + id, e);
        }
    }
}
