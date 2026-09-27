package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks child-issue rollups against PostgreSQL, including exclusion of pull requests.
 */
class LinkedWorkItemContentSourceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    private IdentityProvider provider;
    private Repository repository;
    private long nativeIdSeq = 11_000L;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com")));
        repository = persistRepository("acme/web");
    }

    private Repository persistRepository(String nameWithOwner) {
        Repository created = new Repository();
        created.setNativeId(nextNativeId());
        created.setProvider(provider);
        created.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        created.setNameWithOwner(nameWithOwner);
        created.setHtmlUrl("https://gitlab.example.com/" + nameWithOwner);
        created.setVisibility(Repository.Visibility.PUBLIC);
        created.setDefaultBranch("main");
        created.setCreatedAt(Instant.now());
        created.setUpdatedAt(Instant.now());
        created.setPushedAt(Instant.now());
        return repositoryRepository.save(created);
    }

    @Test
    void shouldCountAParentsChildrenWithClosedOnesAsCompleted() {
        Issue epic = persistIssue(1, Issue.State.OPEN, null);
        Issue other = persistIssue(2, Issue.State.OPEN, null);
        persistIssue(3, Issue.State.CLOSED, epic);
        persistIssue(4, Issue.State.OPEN, epic);
        persistIssue(5, Issue.State.CLOSED, other);
        PullRequest mr = new PullRequest();
        mr.setNativeId(nextNativeId());
        mr.setProvider(provider);
        mr.setNumber(6);
        mr.setTitle("MR !6");
        mr.setState(Issue.State.MERGED);
        mr.setHtmlUrl("https://gitlab.example.com/acme/web/-/merge_requests/6");
        mr.setRepository(repository);
        mr.setParentIssue(epic);
        pullRequestRepository.save(mr);

        IssueRepository.ChildRollup rollup =
                issueRepository.countChildrenByParentIssueId(epic.getId(), Issue.State.CLOSED);
        IssueRepository.ChildRollup none =
                issueRepository.countChildrenByParentIssueId(other.getId() + 1_000, Issue.State.CLOSED);

        assertThat(rollup.getTotal()).isEqualTo(2);
        assertThat(rollup.getCompleted()).isEqualTo(1);
        assertThat(none.getTotal()).isZero();
        assertThat(none.getCompleted()).isZero();
    }

    @Test
    void shouldListTheIssuesAPullRequestClosesWithTheirLabelsInNumberOrder() {
        Issue later = persistIssue(20, Issue.State.OPEN, null);
        Issue earlier = persistIssue(10, Issue.State.CLOSED, null);
        persistIssue(30, Issue.State.OPEN, null);
        PullRequest mr = new PullRequest();
        mr.setNativeId(nextNativeId());
        mr.setProvider(provider);
        mr.setNumber(7);
        mr.setTitle("MR !7");
        mr.setState(Issue.State.OPEN);
        mr.setHtmlUrl("https://gitlab.example.com/acme/web/-/merge_requests/7");
        mr.setRepository(repository);
        mr.replaceClosingIssues(Set.of(later, earlier));
        mr = pullRequestRepository.save(mr);

        // Read outside any transaction, as the projection does.
        List<Issue> closing = pullRequestRepository.findClosingIssuesById(mr.getId());

        assertThat(closing).extracting(Issue::getNumber).containsExactly(10, 20);
        assertThat(closing.get(0).getLabels()).isEmpty();
    }

    @Test
    void shouldLeaveOutAClosingIssueOfAnotherRepositoryEvenWhenItsNumberIsTakenHere() {
        Repository other = persistRepository("acme/api");
        Issue foreign = persistIssue(other, 7, Issue.State.OPEN, null);
        persistIssue(repository, 7, Issue.State.OPEN, null);
        Issue closing = persistIssue(repository, 10, Issue.State.OPEN, null);
        PullRequest mr = new PullRequest();
        mr.setNativeId(nextNativeId());
        mr.setProvider(provider);
        mr.setNumber(8);
        mr.setTitle("MR !8");
        mr.setState(Issue.State.OPEN);
        mr.setHtmlUrl("https://gitlab.example.com/acme/web/-/merge_requests/8");
        mr.setRepository(repository);
        mr.replaceClosingIssues(Set.of(foreign, closing));
        mr = pullRequestRepository.save(mr);

        List<Issue> candidates = pullRequestRepository.findClosingIssuesById(mr.getId());

        assertThat(candidates).extracting(Issue::getId).containsExactly(closing.getId());
        assertThat(pullRequestRepository.countClosingIssuesById(mr.getId())).isEqualTo(1);
    }

    private long nextNativeId() {
        return nativeIdSeq++;
    }

    private Issue persistIssue(int number, Issue.State state, @Nullable Issue parent) {
        return persistIssue(repository, number, state, parent);
    }

    private Issue persistIssue(Repository owner, int number, Issue.State state, @Nullable Issue parent) {
        Issue issue = new Issue();
        issue.setNativeId(nextNativeId());
        issue.setProvider(provider);
        issue.setNumber(number);
        issue.setTitle("Issue #" + number);
        issue.setState(state);
        issue.setHtmlUrl(owner.getHtmlUrl() + "/-/issues/" + number);
        issue.setRepository(owner);
        issue.setParentIssue(parent);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        return issueRepository.save(issue);
    }
}
