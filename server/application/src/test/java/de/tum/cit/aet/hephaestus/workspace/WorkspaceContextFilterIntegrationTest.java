package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceEchoControllers;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceContextFilterIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository;

    @Test
    @WithAdminUser
    void workspaceContextIsInjectedForMembers() {
        User owner = persistUser("admin");
        Workspace workspace = createWorkspace("alpha-space", "Alpha", "alpha", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        WorkspaceEchoControllers.WorkspaceContextSnapshot response = requestContextEcho(workspace.getWorkspaceSlug());

        assertThat(response.contextSlug()).isEqualTo(workspace.getWorkspaceSlug());
        assertThat(response.contextId()).isEqualTo(workspace.getId());
        assertThat(response.roles()).containsExactly("OWNER");
    }

    @Test
    @WithAdminUser
    void invalidSlugReturnsBadRequestProblem() {
        persistUser("admin");

        ProblemDetail problem = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", "INVALID-SLUG")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getDetail()).contains("Invalid workspace slug");
    }

    @Test
    @WithMentorUser
    void nonMembersAreForbiddenFromAccessingWorkspace() {
        persistUser("mentor");
        User workspaceOwner = persistUser("workspace-owner");
        Workspace workspace = createWorkspace("beta-space", "Beta", "beta", AccountType.ORG, workspaceOwner);

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithMentorUser
    void autoSeedDisabledDoesNotGrantAdminToFirstVisitorOfEmptyMembershipWorkspace() {
        // SECURITY (A5): with auto-seed disabled (the production default), the first authenticated
        // visitor of a zero-membership workspace must NOT be silently seeded as ADMIN. A non-member,
        // non-super-admin visitor is forbidden, and no membership row is created.
        User visitor = persistUser("mentor");
        User workspaceOwner = persistUser("empty-membership-owner");
        Workspace workspace = createWorkspace("empty-seed", "Empty", "emptyseed", AccountType.ORG, workspaceOwner);
        // createWorkspace seeds an OWNER membership; strip it so the workspace is genuinely
        // zero-membership — the exact org-sync-churn / admin-only-seeded state A5 guards against.
        workspaceMembershipRepository.deleteAll(workspaceMembershipRepository.findByWorkspace_Id(workspace.getId()));
        assertThat(workspaceMembershipRepository.findByWorkspace_Id(workspace.getId()))
                .isEmpty();

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_IdIn(
                        workspace.getId(), Set.of(visitor.getId())))
                .as("auto-seed disabled must not create a membership")
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void instanceSuperAdminWithoutMembershipIsElevatedToAdmin() {
        // An instance super-admin (APP_ADMIN, app_admin authority) reaches ANY workspace as ADMIN even
        // without an explicit membership — the GitLab admin model (WorkspaceContextFilter elevation).
        // Deliberately ADMIN, never OWNER (ownership is member-granted).
        persistUser("admin");
        User workspaceOwner = persistUser("admin-workspace-owner");
        Workspace workspace = createWorkspace("gamma-space", "Gamma", "gamma", AccountType.ORG, workspaceOwner);

        WorkspaceEchoControllers.WorkspaceContextSnapshot response = requestContextEcho(workspace.getWorkspaceSlug());

        assertThat(response.contextSlug()).isEqualTo(workspace.getWorkspaceSlug());
        assertThat(response.roles()).containsExactly("ADMIN");
    }

    @Test
    @WithAdminUser
    void superAdminElevationDoesNotResurrectSuspendedWorkspace() {
        // The ACTIVE-status gate runs BEFORE the elevation, so a super-admin (no membership) is still
        // 404'd on a suspended workspace's scoped routes — elevation never widens access to non-active tenants.
        persistUser("admin");
        User owner = persistUser("suspended-elev-owner");
        Workspace workspace = createWorkspace("suspended-elev", "Suspended", "suspendedelev", AccountType.ORG, owner);
        workspace.setStatus(WorkspaceStatus.SUSPENDED);
        workspaceRepository.save(workspace);

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void workspaceContextDoesNotLeakBetweenRequests() {
        User owner = persistUser("admin");
        Workspace first = createWorkspace("gamma-space", "Gamma", "gamma", AccountType.ORG, owner);
        Workspace second = createWorkspace("delta-space", "Delta", "delta", AccountType.ORG, owner);

        WorkspaceEchoControllers.WorkspaceContextSnapshot firstResponse = requestContextEcho(first.getWorkspaceSlug());
        WorkspaceEchoControllers.WorkspaceContextSnapshot secondResponse =
                requestContextEcho(second.getWorkspaceSlug());

        assertThat(firstResponse.contextSlug()).isEqualTo(first.getWorkspaceSlug());
        assertThat(secondResponse.contextSlug()).isEqualTo(second.getWorkspaceSlug());
        assertThat(secondResponse.contextSlug()).isNotEqualTo(firstResponse.contextSlug());
    }

    @Test
    @WithAdminUser
    void suspendedWorkspaceBlocksScopedChildRoutes() {
        User owner = persistUser("admin");
        Workspace workspace = createWorkspace("omega-space", "Omega", "omega", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        workspace.setStatus(WorkspaceStatus.SUSPENDED);
        workspaceRepository.save(workspace);

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void suspendedWorkspaceAllowsBaseReadRequests() {
        User owner = persistUser("admin");
        Workspace workspace = createWorkspace("zeta-space", "Zeta", "zeta", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        workspace.setStatus(WorkspaceStatus.SUSPENDED);
        workspaceRepository.save(workspace);

        WorkspaceDTO response = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.workspaceSlug()).isEqualTo(workspace.getWorkspaceSlug());
    }

    @Test
    void unauthenticatedWorkspaceListIsUnauthorized() {
        webTestClient
                .get()
                .uri("/workspaces")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        webTestClient
                .get()
                .uri("/workspaces/")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void unauthenticatedAccessToPublicWorkspaceIsAllowed() {
        User owner = persistUser("public-owner");
        Workspace workspace = createWorkspace("public-space", "Public", "public", AccountType.ORG, owner);
        workspaceService.updatePublicVisibility(workspace.getWorkspaceSlug(), true);

        WorkspaceEchoControllers.WorkspaceContextSnapshot response = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", workspace.getWorkspaceSlug())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceEchoControllers.WorkspaceContextSnapshot.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.contextSlug()).isEqualTo(workspace.getWorkspaceSlug());
        assertThat(response.roles()).isEmpty(); // anonymous viewer
    }

    @Test
    void unauthenticatedAccessToPrivateWorkspaceIsUnauthorized() {
        User owner = persistUser("private-owner");
        Workspace workspace = createWorkspace("private-space", "Private", "private", AccountType.ORG, owner);
        workspaceService.updatePublicVisibility(workspace.getWorkspaceSlug(), false);

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", workspace.getWorkspaceSlug())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void oldSlugRespondsWithPermanentRedirect() {
        User owner = persistUser("redirect-owner");
        Workspace workspace = createWorkspace("old-space", "Old", "old", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        WorkspaceSlugHistory history = new WorkspaceSlugHistory();
        history.setWorkspace(workspace);
        history.setOldSlug("old-space");
        history.setNewSlug("new-space");
        history.setChangedAt(Instant.now());
        workspaceSlugHistoryRepository.save(history);

        workspace.setWorkspaceSlug("new-space");
        workspaceRepository.save(workspace);

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo?foo=bar", "old-space")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isPermanentRedirect()
                .expectHeader()
                .valueEquals(HttpHeaders.LOCATION, "/workspaces/new-space/context-echo?foo=bar")
                .expectBody()
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void oldSlugRedirectsOnPostPreservingLocation() {
        User owner = persistUser("redirect-owner-post");
        Workspace workspace = createWorkspace("old-space-post", "Old", "old", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        WorkspaceSlugHistory history = new WorkspaceSlugHistory();
        history.setWorkspace(workspace);
        history.setOldSlug("old-space-post");
        history.setNewSlug("new-space-post");
        history.setChangedAt(Instant.now());
        workspaceSlugHistoryRepository.save(history);

        workspace.setWorkspaceSlug("new-space-post");
        workspaceRepository.save(workspace);

        webTestClient
                .post()
                .uri("/workspaces/{workspaceSlug}/context-echo?payload=yes", "old-space-post")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"hello\":true}")
                .exchange()
                .expectStatus()
                .isPermanentRedirect()
                .expectHeader()
                .valueEquals(HttpHeaders.LOCATION, "/workspaces/new-space-post/context-echo?payload=yes")
                .expectBody()
                .isEmpty();
    }

    @Test
    void oldSlugForPrivateWorkspaceWithoutMembershipReturnsNotFound() {
        User owner = persistUser("redirect-owner-private");
        Workspace workspace = createWorkspace("secret-space", "Secret", "secret", AccountType.ORG, owner);

        WorkspaceSlugHistory history = new WorkspaceSlugHistory();
        history.setWorkspace(workspace);
        history.setOldSlug("secret-space");
        history.setNewSlug("renamed-space");
        history.setChangedAt(Instant.now());
        workspaceSlugHistoryRepository.save(history);

        workspace.setWorkspaceSlug("renamed-space");
        workspaceRepository.save(workspace);

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", "secret-space")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void expiredSlugReturnsGone() {
        User owner = persistUser("redirect-owner-expired");
        Workspace workspace = createWorkspace("old-expired", "Expired", "expired", AccountType.ORG, owner);

        WorkspaceSlugHistory history = new WorkspaceSlugHistory();
        history.setWorkspace(workspace);
        history.setOldSlug("old-expired");
        history.setNewSlug("new-expired");
        history.setChangedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        history.setRedirectExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        workspaceSlugHistoryRepository.save(history);

        workspace.setWorkspaceSlug("new-expired");
        workspaceRepository.save(workspace);

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", "old-expired")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.GONE)
                .expectBody(ProblemDetail.class)
                .value(problem -> {
                    assertThat(problem.getTitle()).containsIgnoringCase("expired");
                });
    }

    private WorkspaceEchoControllers.WorkspaceContextSnapshot requestContextEcho(String slug) {
        WorkspaceEchoControllers.WorkspaceContextSnapshot response = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/context-echo", slug)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceEchoControllers.WorkspaceContextSnapshot.class)
                .returnResult()
                .getResponseBody();

        return Objects.requireNonNull(response, "Expected workspace context payload");
    }
}
