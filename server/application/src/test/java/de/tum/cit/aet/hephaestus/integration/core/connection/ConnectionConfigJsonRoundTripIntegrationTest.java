package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.util.Set;
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
                Set.of("merge_requests"));
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
                "https://gitlab.example.com", null, null, ConnectionConfig.GitLabConfig.SigningMode.WHSEC, Set.of());
        Long id = persistAndClear(IntegrationKind.GITLAB, "https://gitlab.example.com/whsec", original);

        Connection reloaded = connectionRepository.findById(id).orElseThrow();
        ConnectionConfig.GitLabConfig cfg = (ConnectionConfig.GitLabConfig) reloaded.getConfig();
        assertThat(cfg.signingMode()).isEqualTo(ConnectionConfig.GitLabConfig.SigningMode.WHSEC);
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

    /**
     * The webhook signing secret must survive the JSONB round-trip. This is the persistence half of
     * the API-boundary redaction in {@code ConnectionDetailDTO}: Hibernate serializes this column
     * with the same Jackson {@code ObjectMapper}, so had we redacted with {@code @JsonIgnore} on the
     * record component instead, this test would fail — the stored secret would be destroyed on write.
     */
    @Test
    void outlineConfig_roundTrips_includingTheWebhookSecret() {
        ConnectionConfig.OutlineConfig original = new ConnectionConfig.OutlineConfig(
                "https://outline.example.com", "sub-abc", "ENC:v2:ciphertext", Set.of("documents"));
        Long id = persistAndClear(IntegrationKind.OUTLINE, "https://outline.example.com", original);

        Connection reloaded = connectionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConfig()).isInstanceOf(ConnectionConfig.OutlineConfig.class);
        ConnectionConfig.OutlineConfig cfg = (ConnectionConfig.OutlineConfig) reloaded.getConfig();
        assertThat(cfg.serverUrl()).isEqualTo("https://outline.example.com");
        assertThat(cfg.webhookSubscriptionId()).isEqualTo("sub-abc");
        assertThat(cfg.webhookSecret()).isEqualTo("ENC:v2:ciphertext");
        assertThat(cfg.enabledStreams()).containsExactly("documents");

        assertDiscriminator(id, "OUTLINE");
    }

    /**
     * The unauthenticated webhook hot path resolves its signing secret through the JSONB expression
     * index {@code ix_connection_outline_subscription}. Exercised against real Postgres because the
     * {@code ->>} operator, the quoted projection aliases, and the ACTIVE/OUTLINE predicates only
     * exist in native SQL — a mock cannot tell us the query parses, let alone that it isolates
     * tenants.
     */
    @Test
    void outlineSubscriptionLookup_isKeyedOnTheSubscriptionAndNeverCrossesWorkspaces() {
        Workspace other = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("conn-roundtrip-b"));

        persistActiveOutline(workspace, "sub-a", "secret-a");
        persistActiveOutline(other, "sub-b", "secret-b");
        entityManager.flush();
        entityManager.clear();

        var a = connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-a");
        assertThat(a).hasSize(1);
        assertThat(a.getFirst().getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(a.getFirst().getSigningSecret()).isEqualTo("secret-a");

        var b = connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-b");
        assertThat(b).hasSize(1);
        assertThat(b.getFirst().getWorkspaceId()).isEqualTo(other.getId());
        assertThat(b.getFirst().getSigningSecret()).isEqualTo("secret-b");

        // A forged id matches nothing: the delivery is rejected before any HMAC comparison.
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

    private void persistActiveOutline(Workspace ws, String subscriptionId, String secret) {
        Connection conn = new Connection(
                ws,
                IntegrationKind.OUTLINE,
                "https://outline.example.com/" + subscriptionId,
                new ConnectionConfig.OutlineConfig("https://outline.example.com", subscriptionId, secret, Set.of()));
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
