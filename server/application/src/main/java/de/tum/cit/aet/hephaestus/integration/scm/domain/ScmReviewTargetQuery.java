package de.tum.cit.aet.hephaestus.integration.scm.domain;

import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScmReviewTargetQuery implements ReviewTargetQuery {
    private final IssueRepository issues;
    private final PullRequestRepository pullRequests;
    private final PullRequestReviewRepository reviews;

    @Override
    public Optional<Target> findIssue(long issueId) {
        return issues.findByIdWithAuthorAndRepository(issueId).flatMap(ScmReviewTargetQuery::snapshot);
    }

    @Override
    public Optional<Target> findPullRequest(long pullRequestId) {
        return pullRequests.findByIdWithAuthorAndRepository(pullRequestId).flatMap(ScmReviewTargetQuery::snapshot);
    }

    @Override
    public boolean reviewMatchesTarget(long reviewId, long pullRequestId, long reviewerId) {
        return reviews.existsByIdAndPullRequest_IdAndAuthor_Id(reviewId, pullRequestId, reviewerId);
    }

    private static Optional<Target> snapshot(Issue artifact) {
        var repository = artifact.getRepository();
        if (repository == null || repository.getId() == null) {
            return Optional.empty();
        }
        var author = artifact.getAuthor();
        return Optional.of(new Target(
                repository.getId(),
                repository.getNameWithOwner(),
                artifact.getNumber(),
                author == null ? null : author.getId(),
                artifact.getDeletedAt() != null));
    }
}
