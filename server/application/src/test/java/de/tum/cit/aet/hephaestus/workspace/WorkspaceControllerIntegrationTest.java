package de.tum.cit.aet.hephaestus.workspace;

import static de.tum.cit.aet.hephaestus.leaderboard.LeaguePointsConstants.POINTS_DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserTeamsDTO;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateLeaderboardDigestRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceFeaturesRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceNotificationsRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceScheduleRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceStatusRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceTokenRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMembershipRepository workspaceMembershipRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private WorkspaceLifecycleService workspaceLifecycleService;

    @Autowired
    private WorkspaceMembershipService workspaceMembershipService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionService connectionService;

    @Test
    @WithAdminUser
    void createWorkspaceWithInvalidPayloadReturnsValidationProblemDetail() {
        var request = new CreateWorkspaceRequestDTO("INVALID SLUG", "", "", null, null, null, null, null);

        ProblemDetail problem = webTestClient
                .post()
                .uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Validation failed");
        assertNotNull(problem.getProperties());
        assertThat(problem.getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsKeys("workspaceSlug", "displayName", "accountLogin", "accountType");
    }

    @Test
    void createWorkspaceRequiresAuthentication() {
        User owner = persistUser("unauthenticated-owner");

        var request = new CreateWorkspaceRequestDTO(
                "unauthenticated",
                "Unauthenticated",
                "unauthenticated",
                AccountType.ORG,
                owner.getId(),
                null,
                null,
                null);

        // Anonymous state-changing request: the double-submit CSRF gate (ADR 0017) rejects it 403
        // before authentication runs (no X-XSRF-TOKEN). The mutation is still blocked for anonymous
        // callers; dedicated CSRF + cookie-auth coverage lives in CsrfProtectionIntegrationTest.
        webTestClient
                .post()
                .uri("/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(workspaceRepository.count()).isZero();
    }

    @Test
    @WithMentorUser
    void anyAuthenticatedUserCanCreateWorkspace() {
        User owner = persistUser("mentor");

        var request = new CreateWorkspaceRequestDTO(
                "mentor-space",
                "Mentor Space",
                "mentor-org",
                AccountType.ORG,
                owner.getId(),
                IntegrationKind.GITHUB,
                "ghp_dummy_token_for_test",
                null);

        webTestClient
                .post()
                .uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated();

        assertThat(workspaceRepository.count()).isEqualTo(1);
    }

    @Test
    @WithMentorUser
    void createWorkspaceEndpointAssignsOwnerMembershipAndListsWorkspace() {
        User owner = persistUser("mentor");

        var request = new CreateWorkspaceRequestDTO(
                "controller-space",
                "Controller Space",
                "controller",
                AccountType.ORG,
                owner.getId(),
                IntegrationKind.GITHUB,
                "ghp_dummy_token_for_test",
                null);

        WorkspaceDTO created = webTestClient
                .post()
                .uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        WorkspaceDTO workspace = Objects.requireNonNull(created, "Workspace creation response was null");
        assertThat(workspace.workspaceSlug()).isEqualTo("controller-space");
        assertThat(workspace.status()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE.name());
        assertThat(workspace.providerType()).isEqualTo(IdentityProviderType.GITHUB);
        assertThat(workspace.serverUrl()).isNull();

        Workspace persistedWorkspace =
                workspaceRepository.findById(workspace.id()).orElseThrow();
        ensureAdminMembership(persistedWorkspace);

        WorkspaceDTO fetched = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", workspace.workspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.workspaceSlug()).isEqualTo(workspace.workspaceSlug());

        List<WorkspaceListItemDTO> workspaces = webTestClient
                .get()
                .uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(WorkspaceListItemDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(workspaces).isNotNull();
        assertThat(workspaces).extracting(WorkspaceListItemDTO::workspaceSlug).contains(workspace.workspaceSlug());
        assertThat(workspaces).extracting(WorkspaceListItemDTO::providerType).containsOnly(IdentityProviderType.GITHUB);

        var membership = workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspace.id(), owner.getId())
                .orElseThrow(() -> new AssertionError("Owner membership not created"));
        assertThat(membership.getRole()).isEqualTo(WorkspaceMembership.WorkspaceRole.OWNER);
    }

    @Test
    @WithAdminUser
    void listWorkspacesIsSortedByDisplayName() {
        User ownerAlpha = persistUser("sorted-alpha-owner");
        User ownerZulu = persistUser("sorted-zulu-owner");
        User ownerBravo = persistUser("sorted-bravo-owner");

        Workspace workspaceZulu =
                createWorkspace("sorted-zulu", "Zulu Workspace", "sorted-zulu", AccountType.ORG, ownerZulu);
        Workspace workspaceAlpha =
                createWorkspace("sorted-alpha", "Alpha Workspace", "sorted-alpha", AccountType.ORG, ownerAlpha);
        Workspace workspaceBravo =
                createWorkspace("sorted-bravo", "Bravo Workspace", "sorted-bravo", AccountType.ORG, ownerBravo);

        ensureAdminMembership(workspaceZulu);
        ensureAdminMembership(workspaceAlpha);
        ensureAdminMembership(workspaceBravo);

        List<WorkspaceListItemDTO> workspaces = webTestClient
                .get()
                .uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(WorkspaceListItemDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(workspaces)
                .isNotNull()
                .filteredOn(workspace ->
                        List.of("sorted-zulu", "sorted-alpha", "sorted-bravo").contains(workspace.workspaceSlug()))
                .extracting(WorkspaceListItemDTO::workspaceSlug)
                .containsExactly("sorted-alpha", "sorted-bravo", "sorted-zulu");
    }

    @Test
    @WithAdminUser
    void repositoryListingAndDeletionAreScopedByWorkspaceSlug() {
        User ownerAlpha = persistUser("alpha-owner");
        User ownerBeta = persistUser("beta-owner");

        Workspace workspaceAlpha = createWorkspace("alpha-space", "Alpha", "alpha", AccountType.ORG, ownerAlpha);
        Workspace workspaceBeta = createWorkspace("beta-space", "Beta", "beta", AccountType.ORG, ownerBeta);

        ensureAdminMembership(workspaceAlpha);
        ensureAdminMembership(workspaceBeta);

        RepositoryToMonitor repository = new RepositoryToMonitor();
        repository.setNameWithOwner("acme/demo-repo");
        repository.setWorkspace(workspaceAlpha);
        repository = repositoryToMonitorRepository.save(repository);
        workspaceAlpha.getRepositoriesToMonitor().add(repository);
        workspaceRepository.save(workspaceAlpha);

        String[] alphaRepositories = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/repositories", workspaceAlpha.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String[].class)
                .returnResult()
                .getResponseBody();
        assertThat(alphaRepositories).isNotNull();
        assertThat(alphaRepositories).containsExactly("acme/demo-repo");

        String[] betaRepositories = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/repositories", workspaceBeta.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String[].class)
                .returnResult()
                .getResponseBody();
        assertThat(betaRepositories).isNotNull();
        assertThat(betaRepositories).isEmpty();

        webTestClient
                .delete()
                .uri(
                        "/workspaces/{workspaceSlug}/repositories?nameWithOwner={nameWithOwner}",
                        workspaceBeta.getWorkspaceSlug(),
                        "acme/demo-repo")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound();

        webTestClient
                .delete()
                .uri(
                        "/workspaces/{workspaceSlug}/repositories?nameWithOwner={nameWithOwner}",
                        workspaceAlpha.getWorkspaceSlug(),
                        "acme/demo-repo")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(repositoryToMonitorRepository.findById(repository.getId())).isEmpty();

        Workspace refreshedAlpha =
                workspaceRepository.findById(workspaceAlpha.getId()).orElseThrow();
        assertThat(refreshedAlpha.getRepositoriesToMonitor()).isEmpty();
    }

    @Test
    @WithAdminUser
    void resetLeagueEndpointRequiresExistingWorkspaceAndResetsPoints() {
        User user = persistUser("league-user");
        // Note: leaguePoints is on WorkspaceMembership, not User

        Workspace workspace = createWorkspace("league-space", "League", "league", AccountType.ORG, user);
        ensureAdminMembership(workspace);

        ProblemDetail missingWorkspace = webTestClient
                .put()
                .uri("/workspaces/{workspaceSlug}/league/reset", "unknown-space")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(missingWorkspace).isNotNull();
        assertThat(missingWorkspace.getTitle()).isEqualTo("Resource not found");
        assertThat(missingWorkspace.getDetail()).contains("unknown-space");

        webTestClient
                .put()
                .uri("/workspaces/{workspaceSlug}/league/reset", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk();

        var membership = workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspace.getId(), user.getId())
                .orElseThrow();
        assertThat(membership.getLeaguePoints()).isEqualTo(POINTS_DEFAULT);
    }

    @Test
    @WithAdminUser
    void updateNotificationsEndpointValidatesSlackChannelPattern() {
        User owner = persistUser("notifications-owner");
        Workspace workspace =
                createWorkspace("notifications-space", "Notifications", "notifications", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        // Slack target + credentials live on the Connection registry now. Seed an
        // ACTIVE Slack Connection so updateNotifications has something to update —
        // the OAuth install flow is responsible for this in production.
        seedSlackConnection(workspace);

        webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/notifications", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceNotificationsRequestDTO(true, "core-team", "invalid"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .value(problem -> {
                    assertThat(problem.getTitle()).isEqualTo("Validation failed");
                    assertNotNull(problem.getProperties());
                    assertThat(problem.getProperties()).containsKey("errors");
                    assertThat(problem.getProperties().get("errors"))
                            .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                            .containsKey("channelId");
                });

        webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/notifications", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceNotificationsRequestDTO(true, "core-team", "C12345678"))
                .exchange()
                .expectStatus()
                .isOk();

        Workspace updated = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(updated.getLeaderboardNotificationEnabled()).isTrue();

        ConnectionConfig.SlackConfig slackConfig = connectionService
                .findSlackNotificationConfig(workspace.getId())
                .orElseThrow(
                        () -> new AssertionError("Expected ACTIVE Slack Connection on workspace " + workspace.getId()));
        assertThat(slackConfig.teamLabel()).isEqualTo("core-team");
        assertThat(slackConfig.notificationChannelId()).isEqualTo("C12345678");
    }

    @Test
    @WithAdminUser
    void updateTokenOnAGitHubAppConnectionReturnsConnectionModeConflictProblemDetail() {
        User owner = persistUser("app-token-owner");
        Workspace workspace = createWorkspace("app-token-space", "App Token", "app-token", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        seedGitHubAppConnection(workspace);

        ProblemDetail problem = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/token", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceTokenRequestDTO("ghp_replacement_token_for_test"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Connection mode conflict");
        assertThat(problem.getDetail()).contains("App installation");
    }

    /** An App installation runs on no stored token; the install flow activates this row in production. */
    private void seedGitHubAppConnection(Workspace workspace) {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, "app-token", null, Set.of()));
        connection.setDisplayName("app-token");
        connection.setState(IntegrationState.ACTIVE);
        connectionRepository.save(connection);
    }

    private void seedSlackConnection(Workspace workspace) {
        ConnectionConfig.SlackConfig cfg = new ConnectionConfig.SlackConfig(
                /* teamId */ "T00000000",
                /* teamName */ "Initial Team",
                /* notificationChannelId */ null,
                /* teamLabel */ null,
                /* retentionDays */ null,
                Set.of());
        Connection connection = new Connection(workspace, IntegrationKind.SLACK, "T00000000", cfg);
        connection.setDisplayName("Slack");
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connectionRepository.save(connection);
    }

    @Test
    @WithAdminUser
    void updateScheduleEndpointValidatesPayloadAndPersistsConfiguration() {
        User owner = persistUser("schedule-owner");
        Workspace workspace = createWorkspace("schedule-space", "Schedule", "schedule", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        ProblemDetail invalid = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/schedule", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceScheduleRequestDTO(9, "99:00"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(invalid).isNotNull();
        assertNotNull(invalid.getProperties());
        assertThat(invalid.getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsKeys("day", "time");

        WorkspaceDTO updated = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/schedule", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceScheduleRequestDTO(3, "08:30"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.leaderboardScheduleDay()).isEqualTo(3);
        assertThat(updated.leaderboardScheduleTime()).isEqualTo("08:30");

        Workspace reloaded = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getLeaderboardScheduleDay()).isEqualTo(3);
        assertThat(reloaded.getLeaderboardScheduleTime()).isEqualTo("08:30");
    }

    @Test
    @WithAdminUser
    void leaderboardDigestEndpointAtomicallyPersistsScheduleAndEnabled() {
        User owner = persistUser("digest-owner");
        Workspace workspace = createWorkspace("digest-space", "Digest", "digest", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        ProblemDetail invalid = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/leaderboard-digest", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateLeaderboardDigestRequestDTO(9, "99:00", true, null, null))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(invalid).isNotNull();
        assertNotNull(invalid.getProperties());
        assertThat(invalid.getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsKeys("day", "time");

        // Schedule + enabled are workspace-level (no Slack connection required) — one atomic call.
        WorkspaceDTO updated = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/leaderboard-digest", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateLeaderboardDigestRequestDTO(5, "17:30", true, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.leaderboardScheduleDay()).isEqualTo(5);
        assertThat(updated.leaderboardScheduleTime()).isEqualTo("17:30");
        assertThat(updated.leaderboardNotificationEnabled()).isTrue();

        Workspace reloaded = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getLeaderboardScheduleDay()).isEqualTo(5);
        assertThat(reloaded.getLeaderboardScheduleTime()).isEqualTo("17:30");
        assertThat(reloaded.getLeaderboardNotificationEnabled()).isTrue();
    }

    @Test
    @WithAdminUser
    void unknownWorkspaceReturnsProblemDetail() {
        ProblemDetail problem = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", "missing-space")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Resource not found");
        assertThat(problem.getDetail()).contains("missing-space");
    }

    @Test
    @WithAdminUser
    void duplicateWorkspaceSlugReturnsConflictProblemDetail() {
        User owner = persistUser("duplicate-owner");
        createWorkspace("duplicate-space", "Duplicate", "duplicate", AccountType.ORG, owner);

        var request = new CreateWorkspaceRequestDTO(
                "duplicate-space",
                "Duplicate",
                "duplicate",
                AccountType.ORG,
                owner.getId(),
                IntegrationKind.GITHUB,
                "ghp_dummy_token_for_test",
                null);

        ProblemDetail problem = webTestClient
                .post()
                .uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Workspace slug conflict");
        assertThat(problem.getDetail()).contains("duplicate-space");

        assertThat(workspaceRepository.count()).isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void workspaceUsersEndpointIsScopedToRequestedWorkspace() {
        User ownerAlpha = persistUser("alpha-users-owner");
        User ownerBeta = persistUser("beta-users-owner");
        User contributor = persistUser("workspace-contributor");

        Workspace workspaceAlpha =
                createWorkspace("alpha-users", "Alpha Users", "alpha-users", AccountType.ORG, ownerAlpha);
        Workspace workspaceBeta = createWorkspace("beta-users", "Beta Users", "beta-users", AccountType.ORG, ownerBeta);

        ensureAdminMembership(workspaceAlpha);
        ensureAdminMembership(workspaceBeta);

        workspaceMembershipService.createMembership(
                workspaceAlpha, contributor.getId(), WorkspaceMembership.WorkspaceRole.MEMBER);

        List<UserTeamsDTO> alphaUsers = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/users", workspaceAlpha.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(UserTeamsDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(alphaUsers).isNotNull();
        List<String> alphaLogins = alphaUsers.stream()
                .map(UserTeamsDTO::login)
                .filter(login -> !"admin".equals(login))
                .toList();
        assertThat(alphaLogins).containsExactlyInAnyOrder(ownerAlpha.getLogin(), contributor.getLogin());

        List<UserTeamsDTO> betaUsers = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/users", workspaceBeta.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(UserTeamsDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(betaUsers).isNotNull();
        List<String> betaLogins = betaUsers.stream()
                .map(UserTeamsDTO::login)
                .filter(login -> !"admin".equals(login))
                .toList();
        assertThat(betaLogins).containsExactly(ownerBeta.getLogin());
    }

    @Test
    @WithAdminUser
    void repositoryAlreadyMonitoredReturnsProblemDetail() {
        User owner = persistUser("repo-conflict-owner");
        Workspace workspace =
                createWorkspace("repo-conflict", "Repo Conflict", "repo-conflict", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        RepositoryToMonitor repository = new RepositoryToMonitor();
        repository.setNameWithOwner("acme/test-repo");
        repository.setWorkspace(workspace);
        repository = repositoryToMonitorRepository.save(repository);
        workspace.getRepositoriesToMonitor().add(repository);
        workspaceRepository.save(workspace);

        ProblemDetail problem = webTestClient
                .post()
                .uri(
                        "/workspaces/{workspaceSlug}/repositories?nameWithOwner={nameWithOwner}",
                        workspace.getWorkspaceSlug(),
                        "acme/test-repo")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Repository already monitored");
        assertThat(problem.getDetail()).contains("acme/test-repo");
    }

    @Test
    @WithAdminUser
    void patchingStatusOnPurgedWorkspaceReturnsConflictProblemDetail() {
        User owner = persistUser("purged-owner");
        Workspace workspace = createWorkspace("purged-space", "Purged", "purged", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        workspaceLifecycleService.purgeWorkspace(workspace.getWorkspaceSlug());

        ProblemDetail problem = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/status", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceStatusRequestDTO(Workspace.WorkspaceStatus.ACTIVE))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Workspace lifecycle violation");
        assertThat(problem.getDetail()).contains("purged");
    }

    @Test
    @WithAdminUser
    void patchingStatusToPurgedReturnsConflictAndLeavesWorkspaceActive() {
        User owner = persistUser("purge-bypass-owner");
        Workspace workspace = createWorkspace("purge-bypass", "Purge Bypass", "purge-bypass", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        ProblemDetail problem = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/status", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceStatusRequestDTO(Workspace.WorkspaceStatus.PURGED))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Workspace lifecycle violation");
        assertThat(problem.getDetail()).contains("DELETE").contains("OWNER");

        Workspace unchanged = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
    }

    @Test
    @WithAdminUser
    void updateStatusEndpointTransitionsWorkspaceLifecycle() {
        User owner = persistUser("status-owner");
        Workspace workspace = createWorkspace("status-space", "Status", "status", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        WorkspaceDTO suspended = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/status", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceStatusRequestDTO(Workspace.WorkspaceStatus.SUSPENDED))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(suspended).isNotNull();
        assertThat(suspended.status()).isEqualTo(Workspace.WorkspaceStatus.SUSPENDED.name());

        Workspace suspendedEntity =
                workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(suspendedEntity.getStatus()).isEqualTo(Workspace.WorkspaceStatus.SUSPENDED);

        WorkspaceDTO resumed = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/status", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceStatusRequestDTO(Workspace.WorkspaceStatus.ACTIVE))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(resumed).isNotNull();
        assertThat(resumed.status()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE.name());

        Workspace resumedEntity =
                workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(resumedEntity.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
    }

    @Test
    @WithAdminUser
    void suspendedWorkspaceIsHiddenFromScopedRoutesButAllowsStatusUpdates() {
        User owner = persistUser("suspended-owner");
        Workspace workspace = createWorkspace("suspended-space", "Suspended", "suspended", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        workspaceLifecycleService.suspendWorkspace(workspace.getWorkspaceSlug());

        ProblemDetail hidden = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}/repositories", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(hidden).isNotNull();
        assertThat(hidden.getTitle()).isEqualTo("Resource not found");

        WorkspaceDTO suspended = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(suspended).isNotNull();
        assertThat(suspended.status()).isEqualTo(Workspace.WorkspaceStatus.SUSPENDED.name());

        WorkspaceDTO resumed = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/status", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceStatusRequestDTO(Workspace.WorkspaceStatus.ACTIVE))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(resumed).isNotNull();
        assertThat(resumed.status()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE.name());

        Workspace resumedEntity =
                workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(resumedEntity.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
    }

    @Test
    @WithAdminUser
    void suspendedWorkspaceCanBePurgedViaDeleteEndpoint() {
        User owner = persistUser("purge-suspended-owner");
        Workspace workspace =
                createWorkspace("purge-suspended", "Purge Suspended", "purge-suspended", AccountType.ORG, owner);
        // Delete requires OWNER role, not just ADMIN
        ensureOwnerMembership(workspace);
        workspaceLifecycleService.suspendWorkspace(workspace.getWorkspaceSlug());

        webTestClient
                .delete()
                .uri("/workspaces/{workspaceSlug}", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent();

        Workspace purged = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(purged.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);
    }

    @Test
    @WithAdminUser
    void invalidWorkspaceSlugPathVariableReturnsConstraintViolationProblemDetail() {
        ProblemDetail problem = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", "INVALID SLUG")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Validation failed");
        assertNotNull(problem.getProperties());
        assertThat(problem.getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsKey("workspaceSlug");
    }

    // Feature Flags

    @Test
    @WithAdminUser
    void newWorkspaceHasAllFeatureFlagsDisabledByDefault() {
        User owner = persistUser("feature-owner");
        Workspace workspace = createWorkspace("feature-defaults", "Defaults", "defaults", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        WorkspaceDTO dto = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(dto).isNotNull();
        assertThat(dto.practicesEnabled()).isFalse();
        assertThat(dto.leaderboardEnabled()).isFalse();
        assertThat(dto.progressionEnabled()).isFalse();
        assertThat(dto.practiceReviewAutoTriggerEnabled()).isTrue();
        assertThat(dto.practiceReviewManualTriggerEnabled()).isTrue();
    }

    @Test
    @WithAdminUser
    void shouldOmitRetiredAchievementsWhenReadingWorkspace() {
        User owner = persistUser("retired-features-owner");
        Workspace workspace =
                createWorkspace("retired-features", "Retired features", "retired-features", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.achievementsEnabled")
                .doesNotExist()
                .jsonPath("$.leaderboardEnabled")
                .isEqualTo(false);

        for (String suffix : List.of("", "/definitions")) {
            webTestClient
                    .get()
                    .uri(
                            "/workspaces/{workspaceSlug}/users/{login}/achievements" + suffix,
                            workspace.getWorkspaceSlug(),
                            owner.getLogin())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
        for (String suffix : List.of("/recalculate", "/reload")) {
            webTestClient
                    .post()
                    .uri(
                            "/workspaces/{workspaceSlug}/users/{login}/achievements" + suffix,
                            workspace.getWorkspaceSlug(),
                            owner.getLogin())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
    }

    @Test
    @WithAdminUser
    void updateFeaturesRoundTripEnablesAllFlags() {
        User owner = persistUser("feature-roundtrip-owner");
        Workspace workspace = createWorkspace("feature-roundtrip", "Roundtrip", "roundtrip", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        // PATCH to enable all flags — verify response body
        WorkspaceDTO patchResponse = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(true, null, true, true, true, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(patchResponse).isNotNull();
        assertThat(patchResponse.practicesEnabled()).isTrue();
        assertThat(patchResponse.leaderboardEnabled()).isTrue();
        assertThat(patchResponse.progressionEnabled()).isTrue();

        // Verify via GET (true round-trip through the read path)
        WorkspaceDTO getResponse = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(getResponse).isNotNull();
        assertThat(getResponse.practicesEnabled()).isTrue();
        assertThat(getResponse.leaderboardEnabled()).isTrue();
        assertThat(getResponse.progressionEnabled()).isTrue();
    }

    @Test
    @WithAdminUser
    void updateFeaturesPartialUpdateOnlyChangesSpecifiedFlags() {
        User owner = persistUser("feature-partial-owner");
        Workspace workspace = createWorkspace("feature-partial", "Partial", "partial", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        // Enable leaderboard only — verify response body
        WorkspaceDTO afterFirst = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(null, null, true, null, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(afterFirst).isNotNull();
        assertThat(afterFirst.leaderboardEnabled()).isTrue();
        assertThat(afterFirst.practicesEnabled()).isFalse();
        assertThat(afterFirst.progressionEnabled()).isFalse();

        // Now enable practices — leaderboard should remain true
        WorkspaceDTO afterSecond = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(true, null, null, null, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(afterSecond).isNotNull();
        assertThat(afterSecond.practicesEnabled()).isTrue();
        assertThat(afterSecond.leaderboardEnabled()).isTrue();
        assertThat(afterSecond.progressionEnabled()).isFalse();
    }

    @Test
    @WithAdminUser
    void updateFeaturesExplicitFalseDisablesPreviouslyEnabledFlag() {
        User owner = persistUser("feature-disable-owner");
        Workspace workspace = createWorkspace("feature-disable", "Disable", "disable", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        // Enable all flags first
        webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(true, null, true, true, true, null, null))
                .exchange()
                .expectStatus()
                .isOk();

        // Explicitly disable progression — others should remain true
        WorkspaceDTO afterDisable = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(null, null, null, false, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(afterDisable).isNotNull();
        assertThat(afterDisable.practicesEnabled()).isTrue();
        assertThat(afterDisable.leaderboardEnabled()).isTrue();
        assertThat(afterDisable.progressionEnabled()).isFalse();
    }

    @Test
    @WithAdminUser
    void updateFeaturesTriggerModesRoundTrip() {
        User owner = persistUser("trigger-roundtrip-owner");
        Workspace workspace =
                createWorkspace("trigger-roundtrip", "Trigger Roundtrip", "trigger-roundtrip", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        // Defaults: auto=true, manual=true — disable auto only
        WorkspaceDTO afterDisableAuto = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(null, null, null, null, null, false, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(afterDisableAuto).isNotNull();
        assertThat(afterDisableAuto.practiceReviewAutoTriggerEnabled()).isFalse();
        assertThat(afterDisableAuto.practiceReviewManualTriggerEnabled()).isTrue();

        // Disable manual, re-enable auto
        WorkspaceDTO afterToggle = webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(null, null, null, null, null, true, false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(afterToggle).isNotNull();
        assertThat(afterToggle.practiceReviewAutoTriggerEnabled()).isTrue();
        assertThat(afterToggle.practiceReviewManualTriggerEnabled()).isFalse();

        // Verify via GET
        WorkspaceDTO getResponse = webTestClient
                .get()
                .uri("/workspaces/{workspaceSlug}", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(getResponse).isNotNull();
        assertThat(getResponse.practiceReviewAutoTriggerEnabled()).isTrue();
        assertThat(getResponse.practiceReviewManualTriggerEnabled()).isFalse();
    }

    @Test
    @WithMentorUser
    void updateFeaturesRequiresWorkspaceAdmin() {
        // Create the mentor user to match @WithMentorUser's default username
        persistUser("mentor");

        User owner = persistUser("feature-auth-owner");
        Workspace workspace = createWorkspace("feature-auth", "Auth", "auth", AccountType.ORG, owner);
        // Mentor is intentionally NOT added as a workspace member

        webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(true, null, true, true, true, null, null))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @WithAdminUser
    void featureFlagsAreIncludedInWorkspaceListItems() {
        User owner = persistUser("feature-list-owner");
        Workspace workspace = createWorkspace("feature-list", "FeatureList", "feature-list", AccountType.ORG, owner);
        ensureAdminMembership(workspace);

        // Enable some features
        webTestClient
                .patch()
                .uri("/workspaces/{workspaceSlug}/features", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateWorkspaceFeaturesRequestDTO(true, null, true, false, false, null, null))
                .exchange()
                .expectStatus()
                .isOk();

        // Verify list endpoint includes feature flags
        List<WorkspaceListItemDTO> workspaces = webTestClient
                .get()
                .uri("/workspaces")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(WorkspaceListItemDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(workspaces).isNotNull();
        WorkspaceListItemDTO item = workspaces.stream()
                .filter(ws -> "feature-list".equals(ws.workspaceSlug()))
                .findFirst()
                .orElseThrow();

        assertThat(item.practicesEnabled()).isTrue();
        assertThat(item.leaderboardEnabled()).isTrue();
        assertThat(item.progressionEnabled()).isFalse();
    }
}
