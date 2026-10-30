package de.tum.cit.aet.hephaestus.integration.scm.workspace.settings;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
        ensureAdminMembership(workspace);
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

    private TeamInfoDTO addLabel() {
        return Objects.requireNonNull(webTestClient
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
                .isOk()
                .expectBody(TeamInfoDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private void setTeamHidden(boolean hidden) {
        webTestClient
                .post()
                .uri("/workspaces/{slug}/team/{id}/visibility", workspace.getWorkspaceSlug(), team.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(hidden)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    private void setRepositoryHidden(boolean hidden) {
        webTestClient
                .post()
                .uri(
                        "/workspaces/{slug}/team/{teamId}/repositories/{repositoryId}/visibility",
                        workspace.getWorkspaceSlug(),
                        team.getId(),
                        repository.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(hidden)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    private TeamInfoDTO listedTeam() {
        List<TeamInfoDTO> teams = webTestClient
                .get()
                .uri("/workspaces/{slug}/team", workspace.getWorkspaceSlug())
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
