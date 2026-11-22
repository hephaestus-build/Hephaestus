package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmWorkLookup {
    private final IssueRepository issues;
    private final PullRequestRepository pullRequests;
    private final PullRequestReviewRepository reviews;

    Optional<Issue> find(ItemKind kind, long sourceId) {
        if (kind.equals(ScmItemEvents.PULL_REQUEST))
            return pullRequests.findByIdWithAuthorAndRepository(sourceId).map(Issue.class::cast);
        if (kind.equals(ScmItemEvents.ISSUE)) return issues.findByIdWithAuthorAndRepository(sourceId);
        return Optional.empty();
    }

    /** The stored provider review with {@code sourceId}; refuses as {@link ScmItemService#gone} without one. */
    PullRequestReview review(long sourceId) {
        return reviews.findById(sourceId).orElseThrow(ScmItemService::gone);
    }

    record Work(Issue work, @Nullable PullRequestReview review) {}

    /** The work as stored now: a review's pull request, or the issue or pull request itself. */
    Work current(ItemRef item) {
        long sourceId = Objects.requireNonNull(item.sourceId());
        if (item.kind().equals(ScmItemEvents.PULL_REQUEST_REVIEW)) {
            var review = review(sourceId);
            return new Work(Objects.requireNonNull(review.getPullRequest()), review);
        }
        return new Work(find(item.kind(), sourceId).orElseThrow(ScmItemService::gone), null);
    }
}
