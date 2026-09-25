package de.tum.cit.aet.hephaestus.profile;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Profile reads select work by the author's actor id: a login is unique per provider only, so two
 * namesakes' pull requests must never mix. The unbounded and bounded date ranges both run, because
 * a bind parameter used only in {@code :p IS NULL} needs its explicit CAST for Postgres to type it.
 */
@DisplayName("Profile queries by author id")
class ProfilePullRequestQueryRepositoryIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-15T00:00:00Z");

    @Autowired
    private ProfilePullRequestQueryRepository pullRequestQueries;

    @Autowired
    private ProfileRepositoryQueryRepository repositoryQueries;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    private Workspace workspace;
    private User author;
    private PullRequest authored;
    private Repository contributed;

    @BeforeEach
    void seedNamesakesWork() {
        author = persistUser("shared-author");
        User namesake =
                userRepository.save(TestUserFactory.createUser(700_001L, "shared-author", ensureGitLabProvider()));
        workspace = createWorkspace("profile-queries", "Profile queries", "profile-org", AccountType.ORG, author);
        contributed = persistMonitoredRepository(9401L, "profile-org/authored");
        Repository namesakesRepository = persistMonitoredRepository(9402L, "profile-org/namesake");
        authored = persistOpenPullRequest(9501L, 1, author, contributed);
        persistOpenPullRequest(9502L, 2, namesake, namesakesRepository);
    }

    @Test
    @DisplayName("unbounded and bounded ranges return only the selected author's pull request")
    void shouldReturnOnlyTheSelectedAuthorsPullRequestWhetherTheRangeIsBoundedOrNot() {
        assertThat(pullRequestQueries.findAuthoredByUserIdAndStates(
                        author.getId(), Set.of(Issue.State.OPEN), workspace.getId(), null, null))
                .extracting(PullRequest::getId)
                .containsExactly(authored.getId());
        assertThat(pullRequestQueries.findAuthoredByUserIdAndStates(
                        author.getId(),
                        Set.of(Issue.State.OPEN),
                        workspace.getId(),
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z")))
                .extracting(PullRequest::getId)
                .containsExactly(authored.getId());
    }

    @Test
    @DisplayName("contributed repositories are only the selected author's")
    void shouldReturnOnlyTheSelectedAuthorsRepositoriesWhenANamesakeContributedElsewhere() {
        assertThat(repositoryQueries.findContributedByUserId(author.getId(), workspace.getId()))
                .extracting(Repository::getId)
                .containsExactly(contributed.getId());
    }

    private Repository persistMonitoredRepository(long nativeId, String nameWithOwner) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setProvider(ensureGitHubProvider());
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://github.com/" + nameWithOwner);
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);
        monitors.save(WorkspaceTestFixtures.repositoryMonitor(workspace, nameWithOwner));
        return repository;
    }

    private PullRequest persistOpenPullRequest(
            long nativeId, int number, User pullRequestAuthor, Repository repository) {
        PullRequest pullRequest = new PullRequest();
        pullRequest.setNativeId(nativeId);
        pullRequest.setProvider(repository.getProvider());
        pullRequest.setNumber(number);
        pullRequest.setTitle("Pull request " + number);
        pullRequest.setState(Issue.State.OPEN);
        pullRequest.setAuthor(pullRequestAuthor);
        pullRequest.setRepository(repository);
        pullRequest.setCreatedAt(CREATED_AT);
        pullRequest.setUpdatedAt(CREATED_AT);
        return pullRequestRepository.save(pullRequest);
    }
}
