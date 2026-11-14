package de.tum.cit.aet.hephaestus.integration.scm.workspace.settings;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermissionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The workspace's team settings, written through the operations that the team administration page uses. */
@Tag("integration")
class WorkspaceTeamSettingsIntegrationTest extends AbstractWorkspaceIntegrationTest {

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

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private TeamRepositoryPermissionRepository permissionRepository;

    @Autowired
    private TeamMembershipRepository membershipRepository;

    @Autowired
    private SqlRequestTrace requests;

    private final AtomicLong nativeIds = new AtomicLong(200_000);

    private Workspace workspace;
    private Team team;
    private Repository repository;
    private Label label;

    @BeforeEach
    void setUp() {
        User owner = persistUser("team-settings-owner-" + System.nanoTime());
        String org = "team-settings-org-" + System.nanoTime();
        workspace = createWorkspace("team-settings-" + System.nanoTime(), "Team settings", org, AccountType.ORG, owner);
        linkSyncedOrganization(org);
        team = createTeam("platform", org);
        repository = createRepository(org + "/service");
        label = createLabel("backend", repository);
        connectActivitySource(workspace, ensureGitHubProvider());
        monitor(workspace, repository);
        ensureAdminMembership(workspace);
    }

    @Test
    @WithAdminUser
    void shouldNotFindARepositoryThatOnlyAnotherWorkspaceMonitors() {
        User otherOwner = persistUser("team-settings-other-" + System.nanoTime());
        String otherOrg = "team-settings-other-org-" + System.nanoTime();
        Workspace other = createWorkspace(
                "team-settings-other-" + System.nanoTime(), "Other", otherOrg, AccountType.ORG, otherOwner);
        connectActivitySource(other, ensureGitHubProvider());
        Repository unmonitored = createRepository(otherOrg + "/private");
        Label unmonitoredLabel = createLabel("bug", unmonitored);
        monitor(other, unmonitored);
        permissionRepository.save(
                new TeamRepositoryPermission(team, unmonitored, TeamRepositoryPermission.PermissionLevel.READ));
        // A filter that an earlier version stored for that repository.
        labelFilterRepository.save(new WorkspaceTeamLabelFilter(workspace, team, unmonitoredLabel));

        webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/teams/{teamId}/labels/{labelId}",
                        workspace.getWorkspaceSlug(),
                        team.getId(),
                        unmonitoredLabel.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        webTestClient
                .patch()
                .uri(
                        "/workspaces/{slug}/teams/{teamId}/repositories/{repositoryId}/visibility",
                        workspace.getWorkspaceSlug(),
                        team.getId(),
                        unmonitored.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("hidden", true))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        assertThat(repositorySettingsRepository.findByWorkspaceIdAndTeamIdAndRepositoryId(
                        workspace.getId(), team.getId(), unmonitored.getId()))
                .isEmpty();
        assertThat(labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId()))
                .extracting(filter -> filter.getLabel().getId())
                .containsExactly(unmonitoredLabel.getId());
        TeamInfoDTO listed = listedTeam();
        assertThat(listed.repositories())
                .noneSatisfy(info -> assertThat(info.id()).isEqualTo(unmonitored.getId()));
        assertThat(listed.labels()).extracting(LabelInfoDTO::id).doesNotContain(unmonitoredLabel.getId());
    }

    @Test
    @WithAdminUser
    void shouldListTeamsWithAConstantNumberOfReadsAndNoReadThatPairsRepositoriesWithMembers() {
        staff(team, repository);
        var small = requests.measure(webTestClient, client -> listedTeams(client));
        Team second = createTeam("web", team.getOrganization());
        Team third = createTeam("data", team.getOrganization());
        staff(second, createRepository(team.getOrganization() + "/web"));
        staff(third, createRepository(team.getOrganization() + "/data"));

        var larger = requests.measure(webTestClient, client -> listedTeams(client));

        assertThat(larger.statements())
                .as("one team=%s, three teams=%s", small.statements(), larger.statements())
                .isEqualTo(small.statements());
        // One read of both collections returns a row for every pair of permission and membership.
        assertThat(larger.sql())
                .noneMatch(sql -> sql.contains("team_repository_permission") && sql.contains("team_membership"));
        for (TeamInfoDTO shown : List.of(listedTeam(), addLabel())) {
            assertThat(shown.repositories()).extracting(RepositoryInfoDTO::id).containsExactly(repository.getId());
            assertThat(shown.members()).hasSize(2);
        }
    }

    @Test
    @WithAdminUser
    void shouldKeepOneLabelFilterWhenTheSameLabelIsAddedTwice() {
        addLabel();
        TeamInfoDTO team = addLabel();

        assertThat(team.labels()).extracting(LabelInfoDTO::id).containsExactly(label.getId());
        assertThat(labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), this.team.getId()))
                .singleElement()
                .satisfies(filter -> assertThat(filter.getLabel().getId()).isEqualTo(label.getId()));
    }

    @Test
    @WithAdminUser
    void shouldDeleteTheLabelFilterWhenTheLabelIsRemoved() {
        addLabel();

        for (int attempt = 0; attempt < 2; attempt++) {
            TeamInfoDTO team = Objects.requireNonNull(webTestClient
                    .delete()
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/labels/{labelId}",
                            workspace.getWorkspaceSlug(),
                            this.team.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(TeamInfoDTO.class)
                    .returnResult()
                    .getResponseBody());

            assertThat(team.labels()).isEmpty();
            assertThat(labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), this.team.getId()))
                    .isEmpty();
        }
    }

    @Test
    @WithAdminUser
    void shouldShowTheTeamAgainWhenItIsUnhidden() {
        setTeamHidden(true);
        assertThat(listedTeam().hidden()).isTrue();

        setTeamHidden(false);

        assertThat(listedTeam().hidden()).isFalse();
        assertThat(teamSettingsRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId()))
                .hasValueSatisfying(settings -> assertThat(settings.isHidden()).isFalse());
    }

    @Test
    @WithAdminUser
    void shouldCountTheRepositoryAgainWhenItIsUnhidden() {
        setRepositoryHidden(true);
        assertThat(repositorySettingsRepository.findByWorkspaceIdAndTeamIdAndRepositoryId(
                        workspace.getId(), team.getId(), repository.getId()))
                .hasValueSatisfying(settings ->
                        assertThat(settings.isHiddenFromContributions()).isTrue());

        setRepositoryHidden(false);

        assertThat(repositorySettingsRepository.findByWorkspaceIdAndTeamIdAndRepositoryId(
                        workspace.getId(), team.getId(), repository.getId()))
                .hasValueSatisfying(settings ->
                        assertThat(settings.isHiddenFromContributions()).isFalse());
    }

    @Test
    @WithAdminUser
    void shouldRejectMissingNullAndPrimitiveVisibilityBodiesWithoutChangingSettings() {
        for (String suffix : List.of(
                "/" + team.getId() + "/visibility",
                "/" + team.getId() + "/repositories/" + repository.getId() + "/visibility")) {
            String path = "/workspaces/" + workspace.getWorkspaceSlug() + "/teams" + suffix;
            for (String body : List.of("{}", "{\"hidden\":null}", "true")) {
                webTestClient
                        .patch()
                        .uri(path)
                        .headers(TestAuthUtils.withCurrentUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isBadRequest()
                        .expectBody(Void.class);
            }
            webTestClient
                    .patch()
                    .uri(path + "?hidden=true&hiddenFromContributions=true")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
            webTestClient
                    .post()
                    .uri(path)
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("hidden", true))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(405)
                    .expectBody(Void.class);
        }
        assertThat(teamSettingsRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId()))
                .isEmpty();
        assertThat(repositorySettingsRepository.findByWorkspaceIdAndTeamIdAndRepositoryId(
                        workspace.getId(), team.getId(), repository.getId()))
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldNotAddOrRemoveALabelForAnotherWorkspacesTeam() {
        Team foreign = createTeam("foreign", "another-org");
        for (String method : List.of("PUT", "DELETE")) {
            webTestClient
                    .method(HttpMethod.valueOf(method))
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/labels/{labelId}",
                            workspace.getWorkspaceSlug(),
                            foreign.getId(),
                            label.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
        webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/teams/{teamId}/labels/{labelId}",
                        workspace.getWorkspaceSlug(),
                        team.getId(),
                        Long.MAX_VALUE)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), foreign.getId()))
                .isEmpty();
        assertThat(labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId()))
                .isEmpty();
    }

    @Test
    void shouldRefuseTeamChangesFromAWorkspaceMember() {
        User member = persistUser("team-settings-member-" + System.nanoTime());
        ensureMembership(workspace, member, Membership.WorkspaceRole.MEMBER);
        String token = "mock-jwt-member-" + accountId(member);
        for (String path : List.of(
                "/teams/" + team.getId() + "/visibility",
                "/teams/" + team.getId() + "/repositories/" + repository.getId() + "/visibility")) {
            webTestClient
                    .patch()
                    .uri("/workspaces/" + workspace.getWorkspaceSlug() + path)
                    .headers(headers -> headers.setBearerAuth(token))
                    .bodyValue(Map.of("hidden", true))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
        for (String method : List.of("PUT", "DELETE")) {
            webTestClient
                    .method(HttpMethod.valueOf(method))
                    .uri(
                            "/workspaces/{slug}/teams/{teamId}/labels/{labelId}",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            label.getId())
                    .headers(headers -> headers.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
        assertThat(teamSettingsRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId()))
                .isEmpty();
        assertThat(repositorySettingsRepository.findByWorkspaceIdAndTeamIdAndRepositoryId(
                        workspace.getId(), team.getId(), repository.getId()))
                .isEmpty();
        assertThat(labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId()))
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldLeaveNoLegacyTeamOrLabelRoute() {
        webTestClient
                .get()
                .uri("/workspaces/{slug}/team", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        webTestClient
                .post()
                .uri(
                        "/workspaces/{slug}/teams/{teamId}/labels/{repositoryId}/{label}",
                        workspace.getWorkspaceSlug(),
                        team.getId(),
                        repository.getId(),
                        label.getName())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(labelFilterRepository.findByWorkspaceIdAndTeamId(workspace.getId(), team.getId()))
                .isEmpty();
    }

    private TeamInfoDTO addLabel() {
        return Objects.requireNonNull(webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/teams/{teamId}/labels/{labelId}",
                        workspace.getWorkspaceSlug(),
                        team.getId(),
                        label.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(TeamInfoDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private void setTeamHidden(boolean hidden) {
        webTestClient
                .patch()
                .uri("/workspaces/{slug}/teams/{id}/visibility", workspace.getWorkspaceSlug(), team.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("hidden", hidden))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    private void setRepositoryHidden(boolean hidden) {
        webTestClient
                .patch()
                .uri(
                        "/workspaces/{slug}/teams/{teamId}/repositories/{repositoryId}/visibility",
                        workspace.getWorkspaceSlug(),
                        team.getId(),
                        repository.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("hidden", hidden))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    /** Gives a team one monitored repository and two members. */
    private void staff(Team staffed, Repository monitored) {
        if (!monitored.getId().equals(repository.getId())) monitor(workspace, monitored);
        permissionRepository.save(
                new TeamRepositoryPermission(staffed, monitored, TeamRepositoryPermission.PermissionLevel.WRITE));
        membershipRepository.save(new TeamMembership(
                staffed, persistUser("team-member-" + System.nanoTime()), TeamMembership.Role.MEMBER));
        membershipRepository.save(new TeamMembership(
                staffed, persistUser("team-maintainer-" + System.nanoTime()), TeamMembership.Role.MAINTAINER));
    }

    private List<TeamInfoDTO> listedTeams(WebTestClient client) {
        return Objects.requireNonNull(client.get()
                .uri("/workspaces/{slug}/teams", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(TeamInfoDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private TeamInfoDTO listedTeam() {
        List<TeamInfoDTO> teams = webTestClient
                .get()
                .uri("/workspaces/{slug}/teams", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(TeamInfoDTO.class)
                .returnResult()
                .getResponseBody();
        return Objects.requireNonNull(teams).stream()
                .filter(listed -> team.getId().equals(listed.id()))
                .findFirst()
                .orElseThrow();
    }

    private void linkSyncedOrganization(String login) {
        Organization organization = new Organization();
        organization.setNativeId(nativeIds.incrementAndGet());
        organization.setLogin(login);
        organization.setHtmlUrl("https://github.com/" + login);
        organization.setProvider(ensureGitHubProvider());
        workspace.setOrganizationId(organizationRepository.save(organization).getId());
        workspace = workspaceRepository.save(workspace);
    }

    private Team createTeam(String name, String organization) {
        Team created = new Team();
        created.setNativeId(nativeIds.incrementAndGet());
        created.setName(name);
        created.setSlug(name);
        created.setOrganization(organization);
        created.setDescription("Team " + name);
        created.setHtmlUrl("https://github.com/orgs/" + organization + "/teams/" + name);
        created.setPrivacy(Team.Privacy.VISIBLE);
        created.setProvider(ensureGitHubProvider());
        return teamRepository.save(created);
    }

    private Repository createRepository(String nameWithOwner) {
        Repository created = new Repository();
        created.setNativeId(nativeIds.incrementAndGet());
        created.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        created.setNameWithOwner(nameWithOwner);
        created.setHtmlUrl("https://github.com/" + nameWithOwner);
        created.setDescription("Repository " + nameWithOwner);
        created.setPushedAt(Instant.now());
        created.setCreatedAt(Instant.now());
        created.setUpdatedAt(Instant.now());
        created.setProvider(ensureGitHubProvider());
        return repositoryRepository.save(created);
    }

    private void monitor(Workspace owner, Repository monitored) {
        RepositoryToMonitor created = new RepositoryToMonitor();
        created.setWorkspace(owner);
        created.setNameWithOwner(monitored.getNameWithOwner());
        created.setNativeId(monitored.getNativeId());
        monitorRepository.save(created);
    }

    private Label createLabel(String name, Repository owner) {
        Label created = new Label();
        created.setNativeId(nativeIds.incrementAndGet());
        created.setProvider(ensureGitHubProvider());
        created.setName(name);
        created.setColor("0366d6");
        created.setDescription("Label " + name);
        created.setRepository(owner);
        return labelRepository.save(created);
    }
}
