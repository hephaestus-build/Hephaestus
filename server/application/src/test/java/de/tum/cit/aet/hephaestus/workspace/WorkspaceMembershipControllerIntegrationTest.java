package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AssignRoleRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceMembershipDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceMembershipControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @WithAdminUser
    void listMembersReturnsAllWorkspaceMembersForAdmin() {
        User owner = persistUser("membership-owner");
        Workspace workspace =
                createWorkspace("membership-space", "Membership Space", "membership", AccountType.ORG, owner);

        User adminUser = TestUserFactory.ensureUser(userRepository, "admin", 3L, ensureGitHubProvider());
        workspaceMembershipService.createMembership(workspace, adminUser.getId(), WorkspaceRole.ADMIN);

        User member = persistUser("membership-member");
        workspaceMembershipService.createMembership(workspace, member.getId(), WorkspaceRole.MEMBER);

        List<WorkspaceMembershipDTO> memberships = webTestClient
                .get()
                .uri("/workspaces/{slug}/members", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(WorkspaceMembershipDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(memberships).isNotNull();
        assertThat(memberships)
                .extracting(WorkspaceMembershipDTO::userLogin)
                .containsExactlyInAnyOrder("admin", "membership-member", "membership-owner");
    }

    @Test
    @WithAdminUser
    void adminCanAssignRoleToMember() {
        User owner = persistUser("membership-owner-2");
        Workspace workspace =
                createWorkspace("membership-space-2", "Membership Space 2", "membership2", AccountType.ORG, owner);

        User adminUser = TestUserFactory.ensureUser(userRepository, "admin", 3L, ensureGitHubProvider());
        workspaceMembershipService.createMembership(workspace, adminUser.getId(), WorkspaceRole.ADMIN);

        User targetUser = persistUser("target-user");
        workspaceMembershipService.createMembership(workspace, targetUser.getId(), WorkspaceRole.MEMBER);

        AssignRoleRequestDTO request = new AssignRoleRequestDTO(targetUser.getId(), WorkspaceRole.ADMIN);

        WorkspaceMembershipDTO response = webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceMembershipDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.role()).isEqualTo(WorkspaceRole.ADMIN);

        WorkspaceMembership updated = workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspace.getId(), targetUser.getId())
                .orElseThrow();
        assertThat(updated.getRole()).isEqualTo(WorkspaceRole.ADMIN);
    }

    @Test
    @WithAdminUser
    void updateMemberVisibilityTogglesHiddenFlag() {
        User owner = persistUser("visibility-owner");
        Workspace workspace =
                createWorkspace("visibility-space", "Visibility Space", "visibility", AccountType.ORG, owner);

        User adminUser = TestUserFactory.ensureUser(userRepository, "admin", 3L, ensureGitHubProvider());
        workspaceMembershipService.createMembership(workspace, adminUser.getId(), WorkspaceRole.ADMIN);

        User target = persistUser("visibility-target");
        workspaceMembershipService.createMembership(workspace, target.getId(), WorkspaceRole.MEMBER);

        WorkspaceMembershipDTO hidden = webTestClient
                .patch()
                .uri(
                        "/workspaces/{slug}/members/{userId}/hidden?hidden=true",
                        workspace.getWorkspaceSlug(),
                        target.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceMembershipDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(hidden).isNotNull();
        assertThat(hidden.hidden()).isTrue();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), target.getId()))
                .get()
                .extracting(WorkspaceMembership::isHidden)
                .isEqualTo(true);
    }

    @Test
    @WithAdminUser
    void hiddenFlagIsPreservedWhenOrgSyncOmitsMember() {
        // Simulates the bug where a scheduled GitHub/GitLab org sync — or a transient
        // webhook gap — rebuilds desiredRoles WITHOUT a user that an admin has hidden.
        // The sync used to delete that membership row; a later re-add would re-create
        // it with hidden=false, silently reverting the admin's decision.
        User owner = persistUser("sync-owner");
        Workspace workspace = createWorkspace("sync-space", "Sync Space", "syncorg", AccountType.ORG, owner);

        User hiddenUser = persistUser("hidden-user");
        workspaceMembershipService.createMembership(workspace, hiddenUser.getId(), WorkspaceRole.MEMBER);
        workspaceMembershipService.updateMemberVisibility(workspace.getId(), hiddenUser.getId(), true);

        User visibleUser = persistUser("visible-user");
        workspaceMembershipService.createMembership(workspace, visibleUser.getId(), WorkspaceRole.MEMBER);

        // Org sync runs but does NOT include hiddenUser (e.g. transient API gap, webhook
        // reorder, member temporarily missing from organization_membership).
        // It DOES include a non-hidden member who is also not in the org anymore.
        Map<Long, WorkspaceRole> desiredRoles = new HashMap<>();
        desiredRoles.put(owner.getId(), WorkspaceRole.OWNER);
        // visibleUser and hiddenUser are both absent from desiredRoles

        workspaceMembershipService.syncWorkspaceMembers(workspace, desiredRoles);

        // Hidden member must survive the sync with hidden=true intact.
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), hiddenUser.getId()))
                .as("hidden membership must not be deleted by org sync")
                .get()
                .extracting(WorkspaceMembership::isHidden)
                .isEqualTo(true);

        // Non-hidden absent member is still cleaned up (pre-existing behavior).
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), visibleUser.getId()))
                .as("non-hidden absent member is still removed")
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void hiddenFlagIsPreservedWhenOrgSyncUpdatesUnrelatedMemberRole() {
        // Defensive: ensure an unrelated role change in the same sync transaction
        // does not write-back a stale hidden=false for the hidden member.
        User owner = persistUser("rolechange-owner");
        Workspace workspace =
                createWorkspace("rolechange-space", "Role Change Space", "rolechange", AccountType.ORG, owner);

        User hiddenUser = persistUser("rolechange-hidden");
        workspaceMembershipService.createMembership(workspace, hiddenUser.getId(), WorkspaceRole.MEMBER);
        workspaceMembershipService.updateMemberVisibility(workspace.getId(), hiddenUser.getId(), true);

        User promoted = persistUser("rolechange-promoted");
        workspaceMembershipService.createMembership(workspace, promoted.getId(), WorkspaceRole.MEMBER);

        Map<Long, WorkspaceRole> desiredRoles = new HashMap<>();
        desiredRoles.put(owner.getId(), WorkspaceRole.OWNER);
        desiredRoles.put(hiddenUser.getId(), WorkspaceRole.MEMBER);
        desiredRoles.put(promoted.getId(), WorkspaceRole.ADMIN);

        workspaceMembershipService.syncWorkspaceMembers(workspace, desiredRoles);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), hiddenUser.getId()))
                .get()
                .extracting(WorkspaceMembership::isHidden)
                .isEqualTo(true);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), promoted.getId()))
                .get()
                .extracting(WorkspaceMembership::getRole)
                .isEqualTo(WorkspaceRole.ADMIN);
    }

    @Test
    @WithMentorUser
    void nonMembersCannotAccessMembershipEndpoints() {
        // Create the mentor user to match @WithMentorUser's default username
        persistUser("mentor");

        User owner = persistUser("membership-owner-3");
        Workspace workspace =
                createWorkspace("membership-space-3", "Membership Space 3", "membership3", AccountType.ORG, owner);

        // mentor is intentionally not added to the workspace

        webTestClient
                .get()
                .uri("/workspaces/{slug}/members", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
