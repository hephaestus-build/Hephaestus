package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AssignRoleRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceMembershipDTO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceMembershipControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityLinkRepository identities;

    @Test
    @WithAdminUser
    void listMembersReturnsAllWorkspaceMembersForAdmin() {
        User owner = persistUser("membership-owner");
        Workspace workspace =
                createWorkspace("membership-space", "Membership Space", "membership", AccountType.ORG, owner);

        ensureAdminMembership(workspace);

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
    @WithMentorUser
    void adminCanAssignRoleToMember() {
        User owner = persistUser("membership-owner-2");
        Workspace workspace =
                createWorkspace("membership-space-2", "Membership Space 2", "membership2", AccountType.ORG, owner);

        User admin = persistUser("mentor");
        ensureWorkspaceMembership(workspace, admin, WorkspaceRole.ADMIN);

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
    @WithMentorUser
    void shouldRejectDemotionAndRemovalOfOwnerWhenCallerIsWorkspaceAdmin() {
        User owner = persistUser("protected-owner");
        Workspace workspace = createWorkspace("protected-owner", "Owner", "owner", AccountType.ORG, owner);
        ensureWorkspaceMembership(workspace, persistUser("mentor"), WorkspaceRole.ADMIN);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AssignRoleRequestDTO(owner.getId(), WorkspaceRole.MEMBER))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), owner.getId())
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/members/{userId}", workspace.getWorkspaceSlug(), owner.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden();
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), owner.getId())
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    @WithMentorUser
    void shouldRejectDemotionAndRemovalWhenCallerIsTheLastOwner() {
        User owner = persistUser("mentor");
        Workspace workspace = createWorkspace("last-owner", "Last owner", "owner", AccountType.ORG, owner);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AssignRoleRequestDTO(owner.getId(), WorkspaceRole.ADMIN))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), owner.getId())
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/members/{userId}", workspace.getWorkspaceSlug(), owner.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), owner.getId())
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @WithMentorUser
    void shouldAllowOwnerToLeaveOwnershipWhenAnotherOwnerHasBeenAssigned(boolean remove) {
        User owner = persistUser("mentor");
        Workspace workspace = createWorkspace("owner-transfer", "Transfer", "owner", AccountType.ORG, owner);
        User successor = persistUser("successor");
        ensureWorkspaceMembership(workspace, successor, WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AssignRoleRequestDTO(successor.getId(), WorkspaceRole.OWNER))
                .exchange()
                .expectStatus()
                .isOk();

        if (remove) {
            webTestClient
                    .delete()
                    .uri("/workspaces/{slug}/members/{userId}", workspace.getWorkspaceSlug(), owner.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNoContent();
            assertThat(workspaceMembershipService.findMembership(workspace.getId(), owner.getId()))
                    .isEmpty();
        } else {
            webTestClient
                    .post()
                    .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new AssignRoleRequestDTO(owner.getId(), WorkspaceRole.ADMIN))
                    .exchange()
                    .expectStatus()
                    .isOk();
            assertThat(workspaceMembershipService
                            .getMembership(workspace.getId(), owner.getId())
                            .getRole())
                    .isEqualTo(WorkspaceRole.ADMIN);
        }
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), successor.getId())
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    void shouldRetainOneOwnerWhenBothOwnersDemoteThemselvesConcurrently() throws Exception {
        User first = persistUser("concurrent-owner-one");
        User second = persistUser("concurrent-owner-two");
        Workspace workspace = createWorkspace("concurrent-owners", "Owners", "owners", AccountType.ORG, first);
        ensureWorkspaceMembership(workspace, second, WorkspaceRole.OWNER);
        var barrier = new CyclicBarrier(2);
        var responses = new ArrayList<Future<Integer>>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (User owner : List.of(first, second)) {
                TestUserFactory.ensureAccountForUser(accounts, identities, owner);
                var accountId = identities
                        .findActiveByProviderSubject(
                                Objects.requireNonNull(owner.getProvider().getId()),
                                owner.getNativeId().toString(),
                                null)
                        .orElseThrow()
                        .getAccount()
                        .getId();
                String token = "mock-jwt-user-sub-" + Objects.requireNonNull(accountId);
                responses.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return webTestClient
                            .post()
                            .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                            .headers(headers -> headers.setBearerAuth(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(new AssignRoleRequestDTO(owner.getId(), WorkspaceRole.ADMIN))
                            .exchange()
                            .returnResult(Void.class)
                            .getStatus()
                            .value();
                }));
            }
            assertThat(List.of(
                            responses.get(0).get(20, TimeUnit.SECONDS),
                            responses.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(workspaceMembershipRepository.countByWorkspace_IdAndRole(workspace.getId(), WorkspaceRole.OWNER))
                .isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void updateMemberVisibilityTogglesHiddenFlag() {
        User owner = persistUser("visibility-owner");
        Workspace workspace =
                createWorkspace("visibility-space", "Visibility Space", "visibility", AccountType.ORG, owner);

        ensureAdminMembership(workspace);

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

        User owner = persistUser("sync-owner");
        Workspace workspace = createWorkspace("sync-space", "Sync Space", "syncorg", AccountType.ORG, owner);

        User hiddenUser = persistUser("hidden-user");
        workspaceMembershipService.createMembership(workspace, hiddenUser.getId(), WorkspaceRole.MEMBER);
        workspaceMembershipService.updateMemberVisibility(workspace.getId(), hiddenUser.getId(), true);

        User visibleUser = persistUser("visible-user");
        workspaceMembershipService.createMembership(workspace, visibleUser.getId(), WorkspaceRole.MEMBER);

        Map<Long, WorkspaceRole> desiredRoles = new HashMap<>();
        desiredRoles.put(owner.getId(), WorkspaceRole.OWNER);

        workspaceMembershipService.syncWorkspaceMembers(workspace, desiredRoles);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), hiddenUser.getId()))
                .get()
                .extracting(WorkspaceMembership::isHidden)
                .isEqualTo(true);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), visibleUser.getId()))
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void hiddenFlagIsPreservedWhenOrgSyncUpdatesUnrelatedMemberRole() {

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

        persistUser("mentor");

        User owner = persistUser("membership-owner-3");
        Workspace workspace =
                createWorkspace("membership-space-3", "Membership Space 3", "membership3", AccountType.ORG, owner);

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
