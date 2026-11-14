package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AdminWorkspaceViewDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class WorkspaceAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @BeforeEach
    void setUpAdminIdentity() {
        persistUser("admin");
    }

    private static final String ADMIN_TOKEN = "mock-jwt-token-for-admin-user";
    private static final String CONVERSATION_TOKEN = "mock-jwt-token-for-conversation-user";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void adminListsWorkspacesWithMetadataOnly() {
        User owner = persistUser("acme-owner");
        Workspace ws = createWorkspace("acme-space", "Acme", "acme", AccountType.ORG, owner);
        ensureMembership(ws, owner, WorkspaceRole.OWNER);

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
        assertThat(acme.ownerLogin()).isEqualTo(owner.getName());
        assertThat(acme.memberCount()).isEqualTo(1L);
        assertThat(acme.status()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
    }

    @Test
    void nonAdminIsForbidden() {
        webTestClient
                .get()
                .uri("/admin/workspaces")
                .headers(h -> h.setBearerAuth(CONVERSATION_TOKEN))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
