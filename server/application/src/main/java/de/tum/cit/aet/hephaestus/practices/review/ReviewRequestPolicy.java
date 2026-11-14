package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewRequestPolicy {
    private final ReviewRepository reviews;
    private final PracticeReviewProperties properties;
    private final PracticeReviewCoverageService coverage;
    private final Clock clock;
    private final ReviewGate gate;

    /**
     * Why a member's request for a review of the item is refused, if it is. The review request asks before its pin and
     * again under the workspace lock, so no other request of the workspace changes the counts until its review is
     * recorded.
     */
    public Optional<ReviewRequestReason> refusal(ReviewWorkspace workspace, long itemId, long accountId) {
        long workspaceId = workspace.id();
        var refusal = gate.refusal(workspace, ReviewOrigin.REQUEST);
        if (refusal.isPresent()) return Optional.of(refusal.orElseThrow().eventReason());
        if (!coverage.eligibleAccountIds(workspaceId).contains(accountId))
            return Optional.of(ReviewRequestReason.MEMBER_AI_DECLINED);
        var now = clock.instant();
        int quota = properties.maxRequestsPerRequesterPerHour();
        if (quota > 0
                && reviews.countByWorkspaceIdAndRequestedByIdAndOriginAndCreatedAtGreaterThanEqual(
                                workspaceId, accountId, ReviewOrigin.REQUEST, now.minus(Duration.ofHours(1)))
                        >= quota) return Optional.of(ReviewRequestReason.REQUESTER_QUOTA_EXHAUSTED);
        int cooldown = workspace.reviewSettings().resolveCooldownMinutes(properties.cooldownMinutes());
        if (cooldown > 0
                && reviews.existsByWorkspaceIdAndItemIdAndOriginAndCreatedAtGreaterThanEqual(
                        workspaceId, itemId, ReviewOrigin.REQUEST, now.minus(Duration.ofMinutes(cooldown))))
            return Optional.of(ReviewRequestReason.REQUEST_COOLDOWN_ACTIVE);
        if (reviews.existsByWorkspaceIdAndItemIdAndStatusIn(
                workspaceId, itemId, List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING)))
            return Optional.of(ReviewRequestReason.CONCURRENT_DUPLICATE);
        return Optional.empty();
    }
}
