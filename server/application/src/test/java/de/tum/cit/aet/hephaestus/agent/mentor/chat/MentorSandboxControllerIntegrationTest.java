package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class MentorSandboxControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @WithMentorUser
    void shouldAcceptAPrepareFromAMemberAndReturnAtOnce() {
        User mentor = persistUser("mentor");
        User owner = persistUser("workspace-owner-for-mentor-prepare");
        Workspace workspace =
                createWorkspace("mentor-prepare-space", "Prepare", "mentor-prepare", AccountType.ORG, owner);
        ensureWorkspaceMembership(workspace, mentor, WorkspaceMembership.WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri("/workspaces/{workspaceSlug}/mentor/sandbox", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void shouldRefuseAPrepareFromANonMember() {
        persistUser("mentor");
        User owner = persistUser("workspace-owner-for-mentor-prepare-outsider");
        Workspace workspace = createWorkspace(
                "mentor-prepare-outsider-space", "Outsider", "mentor-prepare-outsider", AccountType.ORG, owner);

        webTestClient
                .post()
                .uri("/workspaces/{workspaceSlug}/mentor/sandbox", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
