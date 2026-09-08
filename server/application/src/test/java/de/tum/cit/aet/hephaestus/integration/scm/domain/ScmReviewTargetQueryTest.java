package de.tum.cit.aet.hephaestus.integration.scm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ScmReviewTargetQueryTest extends BaseUnitTest {
    @Mock
    private IssueRepository issues;

    @Mock
    private PullRequestRepository pullRequests;

    @Mock
    private PullRequestReviewRepository reviews;

    private ScmReviewTargetQuery query;

    @BeforeEach
    void setUp() {
        query = new ScmReviewTargetQuery(issues, pullRequests, reviews);
    }

    @Test
    void shouldReturnAbsenceWhenTargetIsMissing() {
        assertThat(query.findIssue(1L)).isEmpty();
        assertThat(query.findPullRequest(1L)).isEmpty();
    }

    @Test
    void shouldDetachIdentityAndPreserveDeletionWhenReadingTarget() {
        var repository = new Repository();
        repository.setId(12L);
        repository.setNameWithOwner("owner/repo");
        var author = new User();
        author.setId(34L);
        var pr = new PullRequest();
        pr.setRepository(repository);
        pr.setAuthor(author);
        pr.setNumber(56);
        pr.setDeletedAt(Instant.now());
        when(pullRequests.findByIdWithAuthorAndRepository(78L)).thenReturn(Optional.of(pr));

        var target = query.findPullRequest(78L).orElseThrow();
        repository.setNameWithOwner("owner/renamed");
        assertThat(target.repositoryId()).isEqualTo(12L);
        assertThat(target.repositoryFullName()).isEqualTo("owner/repo");
        assertThat(target.number()).isEqualTo(56);
        assertThat(target.authorId()).isEqualTo(34L);
        assertThat(target.deleted()).isTrue();
        pr.setAuthor(null);
        assertThat(query.findPullRequest(78L).orElseThrow().authorId()).isNull();
        pr.setRepository(null);
        assertThat(query.findPullRequest(78L)).isEmpty();
    }
}
