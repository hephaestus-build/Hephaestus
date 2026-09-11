package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AdminWorkspaceViewDTO;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code GET /admin/workspaces} — the instance-admin (app_admin) cross-tenant workspaces overview.
 * Authenticated via the mock-decoder bearer tokens ({@code TestSecurityConfig}); the admin token now
 * carries {@code app_admin}. Verifies the authority gate (a non-admin is 403'd) and that a seeded
 * workspace is surfaced with metadata only (slug, status, owner login, member count) across tenants.
 */
@Tag("integration")
class WorkspaceAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String ADMIN_TOKEN = "mock-jwt-token-for-admin-user";
    private static final String MENTOR_TOKEN = "mock-jwt-token-for-mentor-user";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void adminListsWorkspacesWithMetadataOnly() {
        User owner = persistUser("acme-owner");
        Workspace ws = createWorkspace("acme-space", "Acme", "acme", AccountType.ORG, owner);
        ensureWorkspaceMembership(ws, owner, WorkspaceRole.OWNER);

        List<AdminWorkspaceViewDTO> body = webTestClient
                .get()
                .uri("/admin/workspaces")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(AdminWorkspaceViewDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        AdminWorkspaceViewDTO acme = body.stream()
                .filter(w -> "acme-space".equals(w.workspaceSlug()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("seeded workspace not in the admin overview"));
        assertThat(acme.displayName()).isEqualTo("Acme");
        assertThat(acme.ownerLogin()).isEqualTo("acme-owner");
        assertThat(acme.ownerAccountId()).isNull();
        assertThat(acme.memberCount()).isEqualTo(1L);
        assertThat(acme.status()).isNotBlank();
    }

    @Test
    void nonAdminIsForbidden() {
        webTestClient
                .get()
                .uri("/admin/workspaces")
                .headers(h -> h.setBearerAuth(MENTOR_TOKEN))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
