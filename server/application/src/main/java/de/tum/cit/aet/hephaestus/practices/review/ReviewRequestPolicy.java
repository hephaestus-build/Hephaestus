package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewRequestPolicy {
    private final WorkspaceQueries workspaces;
    private final ReviewRepository reviews;
    private final PracticeReviewProperties properties;
    private final PracticeReviewCoverageService coverage;
    private final Clock clock;
    private final ReviewGate gate;

    @Transactional
    public Optional<ReviewRequestReason> refusal(long workspaceId, long itemId, long accountId) {
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
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
