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
 * The child rollup {@code LinkedWorkItemContentSource} derives when a provider synced none, against a
 * real schema: the unit test mocks the count, so only Postgres can prove the query counts the children
 * of one parent, closed ones as completed, and leaves a merge request out of the count.
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
        repository = new Repository();
        repository.setNativeId(nextNativeId());
        repository.setProvider(provider);
        repository.setName("web");
        repository.setNameWithOwner("acme/web");
        repository.setHtmlUrl("https://gitlab.example.com/acme/web");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository = repositoryRepository.save(repository);
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

    private long nextNativeId() {
        return nativeIdSeq++;
    }

    private Issue persistIssue(int number, Issue.State state, @Nullable Issue parent) {
        Issue issue = new Issue();
        issue.setNativeId(nextNativeId());
        issue.setProvider(provider);
        issue.setNumber(number);
        issue.setTitle("Issue #" + number);
        issue.setState(state);
        issue.setHtmlUrl("https://gitlab.example.com/acme/web/-/issues/" + number);
        issue.setRepository(repository);
        issue.setParentIssue(parent);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        return issueRepository.save(issue);
    }
}
