package de.tum.cit.aet.hephaestus.integration.scm.github;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Abstract base class for live GitHub API integration tests using GraphQL and REST.
 * <p>
 * This class provides pure GraphQL mutations and REST API calls for operations
 * not supported by GraphQL.
 */
public abstract class AbstractGitHubLiveSyncIntegrationTest extends BaseGitHubLiveIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(AbstractGitHubLiveSyncIntegrationTest.class);

    @Autowired
    protected GitHubAppTokenService gitHubAppTokenService;

    @Autowired
    protected WorkspaceRepository workspaceRepository;

    @Autowired
    protected RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    protected IdentityProviderRepository gitProviderRepository;

    @Autowired
    protected ConnectionRepository connectionRepository;

    @Autowired
    protected HttpGraphQlClient gitHubGraphQlClient;

    protected final List<String> repositoriesToDelete = new ArrayList<>();
    protected final List<TeamToDelete> teamsToDelete = new ArrayList<>();

    // Class-level tracking for @AfterAll safety net cleanup
    private final Set<String> allRepositoriesCreated = ConcurrentHashMap.newKeySet();
    private final Set<TeamToDelete> allTeamsCreated = ConcurrentHashMap.newKeySet();

    protected GitHubTestFixtureService fixtureService;
    protected Workspace workspace;
    protected IdentityProvider githubProvider;

    @BeforeAll
    void setUpFixtureService() {
        assumeGitHubCredentialsConfigured();
        String token = gitHubAppTokenService
                .getInstallationTokenDetails(githubInstallationId())
                .token();
        fixtureService = new GitHubTestFixtureService(
                gitHubGraphQlClient
                        .mutate()
                        .header("Authorization", "Bearer " + token)
                        .build(),
                token);
    }

    @BeforeEach
    void initializeWorkspace() {
        databaseTestUtils.cleanDatabase();
        repositoriesToDelete.clear();
        teamsToDelete.clear();
        githubProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        workspace = createWorkspace();
    }

    @AfterEach
    void tearDownGitHubArtifacts() {
        for (String fullName : repositoriesToDelete) {
            fixtureService.deleteRepository(fullName);
            allRepositoriesCreated.remove(fullName); // Remove from safety net if successfully deleted
        }
        for (TeamToDelete team : teamsToDelete) {
            fixtureService.deleteTeam(team.orgLogin(), team.teamSlug());
            allTeamsCreated.remove(team); // Remove from safety net if successfully deleted
        }
    }

    /**
     * Safety net cleanup that runs after all tests in the class.
     * This catches any artifacts that weren't cleaned up by @AfterEach
     * (e.g., due to test failures, exceptions, or deletion failures).
     */
    @AfterAll
    void cleanupOrphanedArtifacts() {
        if (fixtureService == null) {
            return; // Fixture service was never initialized (tests skipped)
        }

        if (!allRepositoriesCreated.isEmpty()) {
            logger.warn(
                    "Safety net cleanup: {} repositories were not cleaned up by @AfterEach",
                    allRepositoriesCreated.size());
            for (String fullName : allRepositoriesCreated) {
                logger.info("Safety net: Deleting orphaned repository {}", fullName);
                fixtureService.deleteRepository(fullName);
            }
            allRepositoriesCreated.clear();
        }

        if (!allTeamsCreated.isEmpty()) {
            logger.warn("Safety net cleanup: {} teams were not cleaned up by @AfterEach", allTeamsCreated.size());
            for (TeamToDelete team : allTeamsCreated) {
                logger.info("Safety net: Deleting orphaned team {}/{}", team.orgLogin(), team.teamSlug());
                fixtureService.deleteTeam(team.orgLogin(), team.teamSlug());
            }
            allTeamsCreated.clear();
        }
    }

    protected Workspace createWorkspace() {
        var ws = new Workspace();
        ws.setWorkspaceSlug(generateWorkspaceSlug());
        ws.setDisplayName(githubOrganization());
        ws.setAccountLogin(githubOrganization());
        ws.setAccountType(AccountType.ORG);
        ws.setRepositorySelection(RepositorySelection.ALL);
        Workspace saved = workspaceRepository.save(ws);

        // Provider classification + installation id live on the Connection registry,
        // not on the Workspace entity. Persist the matching ACTIVE GitHub Connection
        // so downstream components (token resolution, NATS routing, …) can find it.
        ConnectionConfig.GitHubAppConfig cfg = new ConnectionConfig.GitHubAppConfig(
                githubInstallationId(), saved.getAccountLogin(), /* serverUrl */ null, Set.of());
        Connection connection =
                new Connection(saved, IntegrationKind.GITHUB, Long.toString(githubInstallationId()), cfg);
        connection.setDisplayName(saved.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connectionRepository.save(connection);

        return saved;
    }

    private String generateWorkspaceSlug() {
        String lowerCase = githubOrganization().toLowerCase(Locale.ROOT);
        String normalized = lowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            normalized = "workspace-" + Long.toString(System.currentTimeMillis(), 36);
        }
        if (normalized.length() < 3) {
            normalized = normalized + "-ws";
        }
        return normalized;
    }

    protected GitHubTestFixtureService.CreatedRepository createEphemeralRepository(String suffix)
            throws InterruptedException {
        String repositoryName = nextEphemeralSlug(suffix);
        GitHubTestFixtureService.CreatedRepository repository =
                fixtureService.createRepository(githubOrganization(), repositoryName, true);
        repositoriesToDelete.add(repository.fullName());
        allRepositoriesCreated.add(repository.fullName()); // Track in safety net
        return repository;
    }

    protected RepositoryToMonitor registerRepositoryToMonitor(String fullName) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(fullName);
        monitor.setWorkspace(workspace);
        RepositoryToMonitor saved = repositoryToMonitorRepository.save(monitor);
        workspace.getRepositoriesToMonitor().add(saved);
        workspaceRepository.save(workspace);
        return saved;
    }

    protected RepositoryToMonitor registerRepositoryToMonitor(GitHubTestFixtureService.CreatedRepository repository) {
        return registerRepositoryToMonitor(repository.fullName());
    }

    protected List<String> seedOrganizationMembers() {
        return fixtureService.getOrganizationMemberLogins(githubOrganization());
    }

    protected GitHubTestFixtureService.CreatedLabel createRepositoryLabel(
            String repositoryNodeId, String prefix, String color, String description) {
        String uniqueSuffix = Long.toString(Instant.now().toEpochMilli(), 36);
        String labelName = (prefix + "-" + uniqueSuffix).toLowerCase(Locale.ROOT);
        return fixtureService.createLabel(repositoryNodeId, labelName, color, description);
    }

    protected GitHubTestFixtureService.CreatedMilestone createRepositoryMilestone(
            String fullName, String prefix, String description) {
        String uniqueSuffix = Long.toString(Instant.now().toEpochMilli(), 36);
        String milestoneTitle = prefix + " " + uniqueSuffix;
        return fixtureService.createMilestone(fullName, milestoneTitle, description);
    }

    protected CreatedTeam createEphemeralTeam(
            GitHubTestFixtureService.CreatedRepository repository, String maintainerLogin, String permission) {
        String teamName = "it-team-" + nextEphemeralSlug("team");
        GitHubTestFixtureService.CreatedTeam team = fixtureService.createTeam(githubOrganization(), teamName);
        TeamToDelete teamToDelete = new TeamToDelete(githubOrganization(), team.slug());
        teamsToDelete.add(teamToDelete);
        allTeamsCreated.add(teamToDelete); // Track in safety net

        // Add repository to team
        fixtureService.addRepositoryToTeam(githubOrganization(), team.slug(), repository.fullName(), permission);

        return new CreatedTeam(team.databaseId(), team.name(), maintainerLogin);
    }

    protected CreatedIssue createIssueWithComment(GitHubTestFixtureService.CreatedRepository repository)
            throws InterruptedException {
        String issueTitle = "IT issue " + nextEphemeralSlug("issue");
        String issueBody = "Live integration issue created at " + Instant.now();

        // Get repository info for node ID
        GitHubTestFixtureService.RepositoryInfo repoInfo = fixtureService.getRepositoryInfo(repository.fullName());

        GitHubTestFixtureService.CreatedIssue issue =
                fixtureService.createIssue(repoInfo.nodeId(), issueTitle, issueBody);

        String commentBody = "Issue comment seed " + Instant.now();
        GitHubTestFixtureService.CreatedIssueComment comment =
                fixtureService.addIssueComment(issue.nodeId(), commentBody);

        return new CreatedIssue(
                issue.databaseId(), issue.number(), issueTitle, issueBody, comment.databaseId(), commentBody);
    }

    protected PullRequestArtifacts createPullRequestWithReview(GitHubTestFixtureService.CreatedRepository repository)
            throws Exception {
        String branchSuffix = nextEphemeralSlug("branch");
        String branchName = "feature-" + branchSuffix;

        // Get repository info
        GitHubTestFixtureService.RepositoryInfo repoInfo = fixtureService.getRepositoryInfo(repository.fullName());
        String defaultBranch = repoInfo.defaultBranch();
        String headCommitSha = repoInfo.headCommitSha();
        assertNotNull(defaultBranch);
        assertNotNull(headCommitSha);

        // Create feature branch
        fixtureService.createBranch(repoInfo.nodeId(), branchName, headCommitSha);

        // Create a file on the feature branch
        String filePath = "integration/test-" + branchSuffix + ".txt";
        String fileContent = "Integration content generated at " + Instant.now();
        String commitSha = fixtureService.createCommitOnBranch(
                repository.fullName(), branchName, "Add " + filePath, filePath, fileContent);

        // Create pull request
        String prTitle = "IT pull request " + nextEphemeralSlug("pr");
        GitHubTestFixtureService.CreatedPullRequest pullRequest = fixtureService.createPullRequest(
                repoInfo.nodeId(), prTitle, "Integration test PR", branchName, defaultBranch);

        // Add review with comment
        String reviewBody = "Initial review message " + Instant.now();
        String reviewCommentBody = "Review inline note " + Instant.now();

        GitHubTestFixtureService.CreatedReview review = fixtureService.addPullRequestReview(
                pullRequest.nodeId(),
                reviewBody,
                "COMMENT",
                commitSha,
                List.of(new GitHubTestFixtureService.ReviewComment(filePath, 1, reviewCommentBody)));

        return new PullRequestArtifacts(
                pullRequest.databaseId(),
                pullRequest.number(),
                prTitle,
                review.databaseId(),
                reviewBody,
                0L, // Review comment ID - we don't get this from the mutation response
                reviewCommentBody,
                filePath,
                1,
                commitSha);
    }

    protected void awaitCondition(String description, CheckedCondition condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.evaluate()) {
                    return;
                }
            } catch (Exception ex) {
                logger.debug("Condition '{}' not yet satisfied: {}", description, ex.getMessage());
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Timed out waiting for " + description);
    }

    protected void addRepositoryCollaborator(String fullName, String username) {
        fixtureService.addCollaborator(fullName, username, "push");
    }

    protected void removeRepositoryCollaborator(String fullName, String username) {
        fixtureService.removeCollaborator(fullName, username);
    }

    // RECORD TYPES

    protected record CreatedIssue(
            long issueId, int issueNumber, String issueTitle, String issueBody, long commentId, String commentBody) {}

    protected record CreatedTeam(long id, String name, String maintainerLogin) {}

    protected record TeamToDelete(String orgLogin, String teamSlug) {}

    protected record PullRequestArtifacts(
            long pullRequestId,
            int pullRequestNumber,
            String pullRequestTitle,
            long reviewId,
            String reviewBody,
            long reviewCommentId,
            String reviewCommentBody,
            String reviewCommentPath,
            int reviewCommentLine,
            String commitSha) {}

    @FunctionalInterface
    protected interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
