package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AdminWorkspaceViewDTO;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class WorkspaceAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @BeforeEach
    void setUpAdminIdentity() {
        persistUser("admin");
    }

    private static final String ADMIN_TOKEN = "mock-jwt-token-for-admin-user";
    private static final String MENTOR_TOKEN = "mock-jwt-token-for-mentor-user";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void adminListsWorkspacesWithMetadataOnly() {
        User owner = persistUser("acme-owner");
        Workspace ws = createWorkspace("acme-space", "Acme", "acme", AccountType.ORG, owner);
        ensureMembership(ws, owner, WorkspaceRole.OWNER);
        for (int member = 1; member < 5; member++) {
            ensureMembership(ws, persistUser("acme-member-" + member), WorkspaceRole.MEMBER);
        }

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
        assertThat(acme.memberCount()).hasToString("5");
        assertThat(acme.status()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
    }

    @Test
    void shouldWithholdASmallMemberCount() {
        User owner = persistUser("quiet-owner");
        Workspace ws = createWorkspace("quiet-space", "Quiet", "quiet", AccountType.ORG, owner);
        ensureMembership(ws, owner, WorkspaceRole.OWNER);
        ensureMembership(ws, persistUser("quiet-member"), WorkspaceRole.MEMBER);
        jdbcTemplate.update("UPDATE workspace SET status = 'SUSPENDED' WHERE id = ?", ws.getId());

        AdminWorkspaceViewDTO quiet = list().stream()
                .filter(w -> "quiet-space".equals(w.workspaceSlug()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("seeded workspace not in the admin overview"));

        // Two members are fewer than 5. A suspended workspace's member list cannot even be opened.
        assertThat(quiet.memberCount()).isNull();
    }

    private List<AdminWorkspaceViewDTO> list() {
        return Objects.requireNonNull(webTestClient
                .get()
                .uri("/admin/workspaces")
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(AdminWorkspaceViewDTO.class)
                .returnResult()
                .getResponseBody());
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
