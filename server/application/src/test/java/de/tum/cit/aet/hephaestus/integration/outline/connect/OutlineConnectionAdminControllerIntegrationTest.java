package de.tum.cit.aet.hephaestus.integration.outline.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAuditRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient.OutlineIdentity;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineContentClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineTokenClient;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Proof for {@link OutlineConnectionAdminController}: the secret-adjacent
 * {@code /connections/outline/token} endpoints (the health snapshot and manual reconcile trigger were
 * absorbed into the unified sync-observability API — see
 * {@code de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncControllerIntegrationTest} for that
 * authorization coverage). The controller is guarded by a class-level
 * {@code @RequireAtLeastWorkspaceAdmin}; this test pins that a workspace MEMBER is refused (403) while a
 * workspace ADMIN is admitted (200), so a future refactor dropping the annotation cannot ship silently.
 * The key replacement is proved here end to end, on the rows it writes; the probe's behaviour lives in
 * {@link OutlineConnectionAdminServiceTest}.
 */
@TestPropertySource(properties = "hephaestus.integration.outline.enabled=true")
class OutlineConnectionAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String SERVER_URL = "https://outline.example.test";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    @Autowired
    private OutlineTokenClient outlineApiClient;

    @Autowired
    private OutlineContentClient outlineContentClient;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ConnectionAuditRepository connectionAuditRepository;

    private Workspace workspace;

    private Connection connection;

    @AfterEach
    void resetOutlineClient() {
        reset(outlineApiClient, outlineContentClient);
    }

    @BeforeEach
    void setUp() {
        reset(outlineApiClient, outlineContentClient);
        User owner = persistUser("outline-conn-owner-" + System.nanoTime());
        workspace = createWorkspace(
                "outline-conn-" + System.nanoTime(),
                "Outline Connection Test",
                "outline-conn-org",
                AccountType.ORG,
                owner);
        connection = seedActiveOutlineConnection(workspace);

        // Admin path: the token probe reports an accepted key without metadata, and the manual
        // reconcile's background pass (real scheduler, mocked client) stays a harmless no-op.
        lenient()
                .when(outlineApiClient.validateToken(SERVER_URL, "outline-token"))
                .thenReturn(new OutlineIdentity("team-1", "Acme", "user-1"));
        lenient()
                .when(outlineApiClient.describeToken(SERVER_URL, "outline-token"))
                .thenReturn(Optional.empty());
        lenient()
                .when(outlineContentClient.listCollections(anyString(), anyString()))
                .thenReturn(List.of());
        lenient()
                .when(outlineContentClient.listDocuments(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        lenient()
                .when(outlineContentClient.listCollectionDocuments(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
    }

    @Test
    @WithMentorUser
    @DisplayName("a workspace MEMBER cannot reach the token endpoint → 403")
    void nonAdmin_forbidden() {
        User mentor = persistUser("mentor");
        ensureMembership(workspace, mentor, WorkspaceRole.MEMBER);

        tokenRequest().expectStatus().isForbidden().expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    @DisplayName("a workspace ADMIN is admitted: token 200")
    void admin_admitted() {
        ensureAdminMembership(workspace);

        tokenRequest().expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldReplaceTheKeyAndKeepTheConnectionWhenAnAdminEntersAKeyOfTheSameTeam() {
        ensureAdminMembership(workspace);
        lenient()
                .when(outlineApiClient.validateToken(SERVER_URL, "outline-new-token"))
                .thenReturn(new OutlineIdentity("team-1", "Acme", "user-1"));
        lenient()
                .when(outlineApiClient.describeToken(SERVER_URL, "outline-new-token"))
                .thenReturn(Optional.empty());

        replaceRequest("outline-new-token")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accepted")
                .isEqualTo(true);

        assertThat(connectionService.findActiveBearerToken(workspace.getId(), IntegrationKind.OUTLINE))
                .map(BearerToken::token)
                .contains("outline-new-token");
        Connection stored = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(stored.getConfig())
                .isEqualTo(new ConnectionConfig.OutlineConfig(SERVER_URL, "subscription-1", "route-1", Set.of()));
        assertThat(connectionAuditRepository.findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(
                        workspace.getId(), connection.getId()))
                .extracting(ConnectionAudit::getEventType)
                .contains("REPLACE_CREDENTIALS");
    }

    @Test
    @WithAdminUser
    void shouldRefuseAKeyOfAnotherOutlineTeamAndKeepTheStoredKey() {
        ensureAdminMembership(workspace);
        lenient()
                .when(outlineApiClient.validateToken(SERVER_URL, "outline-foreign-token"))
                .thenReturn(new OutlineIdentity("team-2", "Other", "user-2"));

        replaceRequest("outline-foreign-token")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("This key belongs to another Outline team. Use a key of the connected team.");

        assertThat(connectionService.findActiveBearerToken(workspace.getId(), IntegrationKind.OUTLINE))
                .map(BearerToken::token)
                .contains("outline-token");
    }

    @Test
    @WithMentorUser
    void shouldRefuseAMemberWhoTriesToReplaceTheKey() {
        User mentor = persistUser("mentor");
        ensureMembership(workspace, mentor, WorkspaceRole.MEMBER);

        replaceRequest("outline-new-token").expectStatus().isForbidden().expectBody(Void.class);

        assertThat(connectionService.findActiveBearerToken(workspace.getId(), IntegrationKind.OUTLINE))
                .map(BearerToken::token)
                .contains("outline-token");
    }

    // --- helpers ---

    private WebTestClient.ResponseSpec replaceRequest(String token) {
        return webTestClient
                .put()
                .uri("/workspaces/{slug}/connections/outline/token", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", token))
                .exchange();
    }

    private WebTestClient.ResponseSpec tokenRequest() {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/connections/outline/token", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }

    private Connection seedActiveOutlineConnection(Workspace ws) {
        Connection seeded = new Connection(
                ws,
                IntegrationKind.OUTLINE,
                "team-1",
                new ConnectionConfig.OutlineConfig(SERVER_URL, "subscription-1", "route-1", Set.of()));
        seeded.setCredentials(new BearerToken("outline-token", null), credentialConverter);
        ConnectionFixtures.reachState(seeded, IntegrationState.ACTIVE);
        return connectionRepository.save(seeded);
    }
}
