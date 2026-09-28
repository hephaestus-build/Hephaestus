package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.dto.GitHubTeamEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubTeamMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 */
class GitHubTeamMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubTeamMessageHandler handler;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private IdentityProvider gitProvider;
    private Organization testOrganization;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setupTestData();
    }

    private void setupTestData() {
        // Create git provider
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization
        testOrganization = new Organization();
        testOrganization.setNativeId(215361191L);
        testOrganization.setProvider(gitProvider);
        testOrganization.setLogin("HephaestusTest");
        testOrganization.setCreatedAt(Instant.now());
        testOrganization.setUpdatedAt(Instant.now());
        testOrganization.setName("Hephaestus Test");
        testOrganization.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        testOrganization = organizationRepository.save(testOrganization);

        // Create repository (needed for team-repo events)
        Repository repo = new Repository();
        repo.setNativeId(1000663383L);
        repo.setProvider(gitProvider);
        repo.setName("TestRepository");
        repo.setNameWithOwner("HephaestusTest/TestRepository");
        repo.setHtmlUrl("https://github.com/HephaestusTest/TestRepository");
        repo.setVisibility(Repository.Visibility.PUBLIC);
        repo.setDefaultBranch("main");
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setPushedAt(Instant.now());
        repo.setOrganization(testOrganization);
        repositoryRepository.save(repo);

        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(testOrganization);
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("organization.team");
    }

    @Test
    void shouldCreateTeamOnCreatedEvent() throws Exception {
        GitHubTeamEventDTO event = loadPayload("team.org.created");

        // Verify team doesn't exist initially
        assertThat(teamRepository.findByNativeIdAndProviderId(
                        required(event.team().id()), gitProviderId()))
                .isEmpty();

        handler.handleEvent(event);

        assertThat(teamRepository.findByNativeIdAndProviderId(
                        required(event.team().id()), gitProviderId()))
                .isPresent()
                .get()
                .satisfies(team -> {
                    assertThat(team.getNativeId())
                            .isEqualTo(required(event.team().id()));
                    assertThat(team.getName()).isEqualTo(event.team().name());
                    assertThat(team.getDescription()).isEqualTo(event.team().description());
                });
    }

    @Test
    void shouldUpdateTeamOnEditedEvent() throws Exception {
        // Given - first create the team
        GitHubTeamEventDTO createEvent = loadPayload("team.org.created");
        handler.handleEvent(createEvent);

        // Load edited event
        GitHubTeamEventDTO editEvent = loadPayload("team.org.edited");

        handler.handleEvent(editEvent);

        assertThat(teamRepository.findByNativeIdAndProviderId(
                        required(editEvent.team().id()), gitProviderId()))
                .isPresent()
                .get()
                .satisfies(team -> {
                    assertThat(team.getName()).isEqualTo(editEvent.team().name());
                    assertThat(team.getDescription()).isEqualTo(editEvent.team().description());
                });
    }

    @Test
    void shouldDeleteTeamOnDeletedEvent() throws Exception {
        // Given - first create the team
        GitHubTeamEventDTO createEvent = loadPayload("team.org.created");
        handler.handleEvent(createEvent);

        // Verify it exists
        assertThat(teamRepository.findByNativeIdAndProviderId(
                        required(createEvent.team().id()), gitProviderId()))
                .isPresent();

        // Load deleted event
        GitHubTeamEventDTO deleteEvent = loadPayload("team.org.deleted");

        handler.handleEvent(deleteEvent);

        assertThat(teamRepository.findByNativeIdAndProviderId(
                        required(deleteEvent.team().id()), gitProviderId()))
                .isEmpty();
    }

    @Test
    void shouldTakeTheTeamOffPullRequestsThatAskItWhenTheTeamIsDeleted() throws Exception {
        GitHubTeamEventDTO createEvent = loadPayload("team.org.created");
        handler.handleEvent(createEvent);
        Team team = teamRepository
                .findByNativeIdAndProviderId(required(createEvent.team().id()), gitProviderId())
                .orElseThrow();
        PullRequest asking = new PullRequest();
        asking.setNativeId(990_001L);
        asking.setProvider(gitProvider);
        asking.setNumber(990);
        asking.setTitle("Asks the team");
        asking.setState(Issue.State.OPEN);
        asking.setHtmlUrl("https://github.com/HephaestusTest/TestRepository/pull/990");
        asking.setRepository(repositoryRepository
                .findByNameWithOwner("HephaestusTest/TestRepository")
                .orElseThrow());
        PullRequest stored = pullRequestRepository.save(asking);
        stored.replaceRequestedTeams(Set.of(team), Instant.now());
        Long askingId = pullRequestRepository.save(stored).getId();

        handler.handleEvent(loadPayload("team.org.deleted"));

        assertThat(teamRepository.findById(required(team.getId()))).isEmpty();
        Boolean stillAsked = transactionTemplate.execute(status -> !pullRequestRepository
                .findById(required(askingId))
                .orElseThrow()
                .getRequestedTeams()
                .isEmpty());
        assertThat(stillAsked).isFalse();
    }

    private GitHubTeamEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubTeamEventDTO.class);
    }

    private Long gitProviderId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
