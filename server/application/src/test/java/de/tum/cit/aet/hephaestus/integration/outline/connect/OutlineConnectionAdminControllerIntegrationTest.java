package de.tum.cit.aet.hephaestus.integration.outline.connect;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
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
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Authorization proof for {@link OutlineConnectionAdminController} — now just the secret-adjacent
 * {@code /connections/outline/token} endpoint (the health snapshot and manual reconcile trigger were
 * absorbed into the unified sync-observability API — see
 * {@code de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncControllerIntegrationTest} for that
 * authorization coverage). The controller is guarded by a class-level
 * {@code @RequireAtLeastWorkspaceAdmin}; this test pins that a workspace MEMBER is refused (403) while a
 * workspace ADMIN is admitted (200), so a future refactor dropping the annotation cannot ship silently.
 * Business behaviour lives in {@link OutlineConnectionAdminServiceTest} — this stays scoped to access
 * control.
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

    private Workspace workspace;

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
        seedActiveOutlineConnection(workspace);

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
        ensureWorkspaceMembership(workspace, mentor, WorkspaceRole.MEMBER);

        tokenRequest().expectStatus().isForbidden().expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    @DisplayName("a workspace ADMIN is admitted: token 200")
    void admin_admitted() {
        ensureAdminMembership(workspace);

        tokenRequest().expectStatus().isOk().expectBody(Void.class);
    }

    // --- helpers ---

    private WebTestClient.ResponseSpec tokenRequest() {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/connections/outline/token", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }

    private void seedActiveOutlineConnection(Workspace ws) {
        Connection connection = new Connection(
                ws,
                IntegrationKind.OUTLINE,
                "team-1",
                new ConnectionConfig.OutlineConfig(SERVER_URL, null, null, Set.of()));
        connection.setCredentials(new BearerToken("outline-token", null), credentialConverter);
        connection.setState(IntegrationState.ACTIVE);
        connectionRepository.save(connection);
    }
}
