package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

// The response body is instance-wide; only the HTTP status proves that the workspace membership gate ran.
class WorkspaceLlmSettingsControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private InstanceLlmSettingsRepository instanceLlmSettingsRepository;

    private Workspace setupWorkspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Workspace " + slug, slug + "-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        return workspace;
    }

    @Test
    @WithAdminUser
    void aWorkspaceAdminReadsTheInstancePolicy() {
        Workspace workspace = setupWorkspace("llmsettings-read-ws");
        InstanceLlmSettings settings = new InstanceLlmSettings();
        settings.setId((short) 1);
        settings.setAllowWorkspaceConnections(false);
        instanceLlmSettingsRepository.save(settings);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/settings", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.ownProviderAllowed")
                .isEqualTo(false);
    }

    @Test
    @WithMentorUser
    void aWorkspaceMemberCannotReachTheEndpoint() {
        Workspace workspace = setupWorkspace("llmsettings-member-ws");
        // Login must match @WithMentorUser's default "mentor" principal so the membership resolver
        // finds this exact row.
        User member = persistUser("mentor");
        ensureMembership(workspace, member, WorkspaceRole.MEMBER);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/settings", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void shouldNotFindAnotherWorkspaceButStillAdmitTheAdminOfTheirOwn() {
        // A genuine (non-superadmin) workspace ADMIN: @WithAdminUser carries the instance-wide
        // app_admin elevation, which would admit this call for the wrong reason.
        User admin = persistUser("mentor");
        Workspace own = createWorkspace("llmsettings-own-ws", "Own", "llmsettings-own-org", AccountType.ORG, admin);
        ensureMembership(own, admin, WorkspaceRole.ADMIN);

        Workspace other = createWorkspace(
                "llmsettings-other-ws",
                "Other",
                "llmsettings-other-org",
                AccountType.ORG,
                persistUser("llmsettings-other-owner"));

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/settings", other.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/llm/settings", own.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }
}
