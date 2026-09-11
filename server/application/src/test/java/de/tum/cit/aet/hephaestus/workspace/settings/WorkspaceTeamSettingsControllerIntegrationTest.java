package de.tum.cit.aet.hephaestus.workspace.settings;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.dto.UpdateRepositorySettingsRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.settings.dto.UpdateTeamSettingsRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.settings.dto.WorkspaceTeamRepositorySettingsDTO;
import de.tum.cit.aet.hephaestus.workspace.settings.dto.WorkspaceTeamSettingsDTO;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for {@link WorkspaceTeamSettingsController}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Team visibility settings (hidden)</li>
 *   <li>Repository contribution visibility settings</li>
 *   <li>Label filter management</li>
 *   <li>Security requirements (admin-only mutations)</li>
 * </ul>
 */
@Tag("integration")
class WorkspaceTeamSettingsControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private WorkspaceTeamSettingsRepository teamSettingsRepository;

    @Autowired
    private WorkspaceTeamRepositorySettingsRepository repositorySettingsRepository;

    @Autowired
    private WorkspaceTeamLabelFilterRepository labelFilterRepository;

    private final AtomicLong entityIdGenerator = new AtomicLong(100_000);

    private Workspace workspace;
    private Team team;
    private Repository repository;
    private Label label;
    private User owner;

    @BeforeEach
    void setUp() {
        // Arrange: Create base test data
        owner = persistUser("settings-owner-" + System.nanoTime());
        workspace = createWorkspace(
                "settings-test-" + System.nanoTime(), "Settings Test", "settings-org", AccountType.ORG, owner);
        linkSyncedOrganization("settings-org");
        team = createTeam("test-team", "settings-org");
        repository = createRepository("settings-org/test-repo");
        label = createLabel("feature", "0366d6", repository);
    }

    /**
     * Links the synced {@link Organization} that install creates before any team is synced
     * ({@code GithubLifecycleListener}). Team scoping resolves the workspace's provider through it, so
     * without this the workspace has no resolvable team scope and correctly sees no teams.
     */
    private void linkSyncedOrganization(String login) {
        Organization organization = new Organization();
        organization.setNativeId(entityIdGenerator.incrementAndGet());
        organization.setLogin(login);
        organization.setHtmlUrl("https://github.com/" + login);
        organization.setProvider(ensureGitHubProvider());
        workspace.setOrganization(organizationRepository.save(organization));
        workspace = workspaceRepository.save(workspace);
    }

    // Team Visibility Settings Tests

    @Nested
    class TeamVisibilitySettingsTests {

        @Test
        @WithAdminUser
        void getTeamSettings_asAdmin_shouldReturnSettings() {
            ensureAdminMembership(workspace);

            WorkspaceTeamSettingsDTO result = webTestClient
                    .get()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(WorkspaceTeamSettingsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.workspaceId()).isEqualTo(workspace.getId());
            assertThat(result.teamId()).isEqualTo(team.getId());
            assertThat(result.hidden()).isFalse();
        }

        @Test
        void getTeamSettings_asNonMember_shouldReturn403() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), team.getId())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("PATCH /teams/{teamId}/settings - as admin updates and returns 200")
        void updateTeamHidden_asAdmin_shouldUpdateAndReturn200() {
            ensureAdminMembership(workspace);
            var request = new UpdateTeamSettingsRequestDTO(true);

            WorkspaceTeamSettingsDTO result = webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(WorkspaceTeamSettingsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.hidden()).isTrue();

            // Verify database state
            var savedSettings = teamSettingsRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId());
            assertThat(savedSettings).isPresent();
            assertThat(savedSettings.get().isHidden()).isTrue();
        }

        @Test
        @WithMentorUser
        @DisplayName("PATCH /teams/{teamId}/settings - as non-admin returns 403")
        void updateTeamHidden_asNonAdmin_shouldReturn403() {
            persistUser("mentor");
            User memberUser = persistUser("member-" + System.nanoTime());
            ensureWorkspaceMembership(workspace, memberUser, WorkspaceMembership.WorkspaceRole.MEMBER);
            var request = new UpdateTeamSettingsRequestDTO(true);

            webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("PATCH /teams/{teamId}/settings - invalid team returns 404")
        void updateTeamHidden_invalidTeam_shouldReturn404() {
            ensureAdminMembership(workspace);
            var request = new UpdateTeamSettingsRequestDTO(true);
            Long nonExistentTeamId = 999999L;

            webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), nonExistentTeamId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    // Repository Visibility Settings Tests

    @Nested
    class RepositoryVisibilitySettingsTests {

        @Test
        @WithAdminUser
        void getRepositorySettings_asAdmin_shouldReturnSettings() {
            ensureAdminMembership(workspace);

            WorkspaceTeamRepositorySettingsDTO result = webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/repositories/{repoId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            repository.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(WorkspaceTeamRepositorySettingsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.workspaceId()).isEqualTo(workspace.getId());
            assertThat(result.teamId()).isEqualTo(team.getId());
            assertThat(result.repositoryId()).isEqualTo(repository.getId());
            assertThat(result.hiddenFromContributions()).isFalse();
        }

        @Test
        void getRepositorySettings_asNonMember_shouldReturn403() {
            webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/repositories/{repoId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            repository.getId())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("PATCH /teams/{teamId}/settings/repositories/{repoId} - as admin updates and returns 200")
        void updateRepositoryHidden_asAdmin_shouldUpdateAndReturn200() {
            ensureAdminMembership(workspace);
            var request = new UpdateRepositorySettingsRequestDTO(true);

            WorkspaceTeamRepositorySettingsDTO result = webTestClient
                    .patch()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/repositories/{repoId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            repository.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(WorkspaceTeamRepositorySettingsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.hiddenFromContributions()).isTrue();

            // Verify database state
            var savedSettings = repositorySettingsRepository.findByWorkspaceIdAndTeamIdAndRepositoryId(
                    workspace.getId(), team.getId(), repository.getId());
            assertThat(savedSettings).isPresent();
            assertThat(savedSettings.get().isHiddenFromContributions()).isTrue();
        }

        @Test
        @WithMentorUser
        @DisplayName("PATCH /teams/{teamId}/settings/repositories/{repoId} - as non-admin returns 403")
        void updateRepositoryHidden_asNonAdmin_shouldReturn403() {
            persistUser("mentor");
            User memberUser = persistUser("member-repo-" + System.nanoTime());
            ensureWorkspaceMembership(workspace, memberUser, WorkspaceMembership.WorkspaceRole.MEMBER);
            var request = new UpdateRepositorySettingsRequestDTO(true);

            webTestClient
                    .patch()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/repositories/{repoId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            repository.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        @DisplayName("PATCH /teams/{teamId}/settings/repositories/{repoId} - invalid repository returns 404")
        void updateRepositoryHidden_invalidRepository_shouldReturn404() {
            ensureAdminMembership(workspace);
            var request = new UpdateRepositorySettingsRequestDTO(true);
            Long nonExistentRepoId = 999999L;

            webTestClient
                    .patch()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/repositories/{repoId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            nonExistentRepoId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    // Label Filter Settings Tests

    @Nested
    class LabelFilterSettingsTests {

        @Test
        @WithAdminUser
        void getLabelFilters_asAdmin_shouldReturnFilters() {
            ensureAdminMembership(workspace);

            List<LabelInfoDTO> result = webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters",
                            workspace.getWorkspaceSlug(),
                            team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBodyList(LabelInfoDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result).isEmpty();
        }

        @Test
        @WithAdminUser
        @DisplayName("POST /teams/{teamId}/settings/label-filters/{labelId} - as admin adds and returns 201")
        void addLabelFilter_asAdmin_shouldAddAndReturn201() {
            ensureAdminMembership(workspace);

            webTestClient
                    .post()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            // Assert: Verify database state
            var filters = labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId());
            assertThat(filters).hasSize(1);
            assertThat(filters.get(0).getLabel().getId()).isEqualTo(label.getId());
        }

        @Test
        @WithMentorUser
        @DisplayName("POST /teams/{teamId}/settings/label-filters/{labelId} - as non-admin returns 403")
        void addLabelFilter_asNonAdmin_shouldReturn403() {
            persistUser("mentor");
            User memberUser = persistUser("member-label-" + System.nanoTime());
            ensureWorkspaceMembership(workspace, memberUser, WorkspaceMembership.WorkspaceRole.MEMBER);

            webTestClient
                    .post()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void addLabelFilter_duplicateLabel_shouldReturnExisting() {
            ensureAdminMembership(workspace);

            // Add the filter first
            webTestClient
                    .post()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            // Act: Try to add the same filter again
            webTestClient
                    .post()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            // Assert: Only one filter exists
            var filters = labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId());
            assertThat(filters).hasSize(1);
        }

        @Test
        @WithAdminUser
        @DisplayName("DELETE /teams/{teamId}/settings/label-filters/{labelId} - as admin removes and returns 204")
        void removeLabelFilter_asAdmin_shouldRemoveAndReturn204() {
            ensureAdminMembership(workspace);
            // First add the filter
            webTestClient
                    .post()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            // Verify filter exists
            var filtersBefore = labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId());
            assertThat(filtersBefore).hasSize(1);

            webTestClient
                    .delete()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody(Void.class);

            // Assert: Verify database state
            var filtersAfter = labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId());
            assertThat(filtersAfter).isEmpty();
        }

        @Test
        @WithAdminUser
        @DisplayName("DELETE /teams/{teamId}/settings/label-filters/{labelId} - not exists returns 404")
        void removeLabelFilter_notExists_shouldReturn404() {
            ensureAdminMembership(workspace);
            Long nonExistentLabelId = 999999L;

            webTestClient
                    .delete()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            nonExistentLabelId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void getLabelFilters_afterAdding_shouldReturnFilters() {
            ensureAdminMembership(workspace);
            Label secondLabel = createLabel("bug", "d73a4a", repository);

            // Add two filters
            webTestClient
                    .post()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            webTestClient
                    .post()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            secondLabel.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(Void.class);

            List<LabelInfoDTO> result = webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/label-filters",
                            workspace.getWorkspaceSlug(),
                            team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBodyList(LabelInfoDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result).hasSize(2);
            assertThat(result).extracting(LabelInfoDTO::name).containsExactlyInAnyOrder("feature", "bug");
        }
    }

    // XP Filtering Integration Tests

    @Nested
    class XpFilteringIntegrationTests {

        @Test
        @WithAdminUser
        void hiddenTeamSettings_shouldPersistAcrossRequests() {
            ensureAdminMembership(workspace);

            // Act: Hide the team
            webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdateTeamSettingsRequestDTO(true))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            // Assert: Verify the team is hidden when fetching settings
            WorkspaceTeamSettingsDTO result = webTestClient
                    .get()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(WorkspaceTeamSettingsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.hidden()).isTrue();
        }

        @Test
        @WithAdminUser
        void hiddenRepositorySettings_shouldPersistAcrossRequests() {
            ensureAdminMembership(workspace);

            // Act: Hide the repository from contributions
            webTestClient
                    .patch()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/repositories/{repoId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            repository.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdateRepositorySettingsRequestDTO(true))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            // Assert: Verify the repository is hidden when fetching settings
            WorkspaceTeamRepositorySettingsDTO result = webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/settings/repositories/{repoId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            repository.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(WorkspaceTeamRepositorySettingsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.hiddenFromContributions()).isTrue();
        }

        @Test
        @WithAdminUser
        void toggleTeamVisibility_shouldWorkBothWays() {
            ensureAdminMembership(workspace);

            // Act: Hide the team
            webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdateTeamSettingsRequestDTO(true))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            // Act: Unhide the team
            WorkspaceTeamSettingsDTO result = webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/teams/{teamId}/settings", workspace.getWorkspaceSlug(), team.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new UpdateTeamSettingsRequestDTO(false))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(WorkspaceTeamSettingsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(result).isNotNull();
            assertThat(result.hidden()).isFalse();
        }
    }

    // Test Helper Methods

    /**
     * Creates and persists a team entity for testing.
     *
     * @param name the team name
     * @param organization the organization name (should match workspace.accountLogin)
     * @return the persisted team
     */
    private Team createTeam(String name, String organization) {
        Team newTeam = new Team();
        newTeam.setNativeId(entityIdGenerator.incrementAndGet());
        newTeam.setName(name);
        newTeam.setSlug(name);
        newTeam.setOrganization(organization);
        newTeam.setDescription("Test team: " + name);
        newTeam.setHtmlUrl("https://github.com/orgs/" + organization + "/teams/" + name);
        newTeam.setPrivacy(Team.Privacy.VISIBLE);
        newTeam.setProvider(ensureGitHubProvider());
        return teamRepository.save(newTeam);
    }

    /**
     * Creates and persists a repository entity for testing.
     *
     * @param nameWithOwner the full repository name (e.g., "org/repo")
     * @return the persisted repository
     */
    private Repository createRepository(String nameWithOwner) {
        String[] parts = nameWithOwner.split("/");
        String repoName = parts.length > 1 ? parts[1] : nameWithOwner;

        Repository repo = new Repository();
        repo.setNativeId(entityIdGenerator.incrementAndGet());
        repo.setName(repoName);
        repo.setNameWithOwner(nameWithOwner);
        repo.setHtmlUrl("https://github.com/" + nameWithOwner);
        repo.setDescription("Test repository: " + nameWithOwner);
        repo.setPushedAt(Instant.now());
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setProvider(ensureGitHubProvider());
        return repositoryRepository.save(repo);
    }

    /**
     * Creates and persists a label entity for testing.
     *
     * @param name the label name
     * @param color the label color (hex without #)
     * @param labelRepository the repository the label belongs to
     * @return the persisted label
     */
    private Label createLabel(String name, String color, Repository labelRepository) {
        Label newLabel = new Label();
        newLabel.setNativeId(entityIdGenerator.incrementAndGet());
        newLabel.setProvider(ensureGitHubProvider());
        newLabel.setName(name);
        newLabel.setColor(color);
        newLabel.setDescription("Test label: " + name);
        newLabel.setRepository(labelRepository);
        return this.labelRepository.save(newLabel);
    }
}
