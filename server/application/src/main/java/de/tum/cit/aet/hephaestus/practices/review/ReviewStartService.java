package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewFreshness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ReviewStartService implements ReviewStart {
    private final ReviewRepository reviews;
    private final ReviewItemRepository memberships;
    private final ReviewPracticeRepository assignments;
    private final ProductItemQuery items;
    private final PracticeItemOptions itemOptions;
    private final ItemEventService events;
    private final Clock clock;
    private final FeedbackRepair repairs;
    private final ReviewFreshness membership;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean start(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        return startLocked(review);
    }

    private boolean startLocked(Review review) {
        if (review.getStatus().isTerminal()
                || review.getChecks().path("cancellationRequested").asBoolean()) return false;
        if (review.getStatus() == ReviewStatus.QUEUED) {
            review.setStatus(ReviewStatus.RUNNING);
            var checks = (ObjectNode) review.getChecks().deepCopy();
            checks.put("startedAt", clock.instant().toString());
            review.setChecks(checks);
        }
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean compose(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (!review.getStatus().isTerminal()) return startLocked(review);
        return (review.getStatus() == ReviewStatus.COMPLETE || review.getStatus() == ReviewStatus.PARTIAL)
                && !review.getChecks().path("cancellationRequested").asBoolean()
                && repairs.pending(workspaceId, reviewId);
    }

    // A person's request stays. Only a newer waiting occasion with changed readable sources replaces an event review.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean replaceByNewerOccasion(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus() != ReviewStatus.QUEUED
                || review.getKind() != ReviewKind.ITEM
                || review.getOrigin() != ReviewOrigin.EVENT
                || review.getChecks().path("cancellationRequested").asBoolean()) return false;
        var kind = items.find(workspaceId, review.getItemId()).map(ItemRef::kind);
        if (kind.isEmpty()) return false;
        boolean moved = events.open(workspaceId, review.getItemId()).stream()
                .anyMatch(waiting -> waiting.itemVersion() != null
                        && waiting.occurredAt().isAfter(review.getOrderedAt())
                        && itemOptions
                                .occasion(kind.orElseThrow(), waiting.name())
                                .joinsWindow());
        if (!moved || membership.capturedCurrentness(workspaceId, reviewId) != Currentness.STALE) return false;
        var now = clock.instant();
        String reason = ReviewStopReason.REPLACED.name();
        for (var member :
                memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspaceId, reviewId)) {
            if (member.getState() != ReviewItemState.LISTED) continue;
            member.withhold(reason, "A newer update of the work replaced this review before it started.", now);
        }
        for (var assignment : assignments.lockAssignments(workspaceId, reviewId)) {
            if (assignment.getStatus() != ReviewPracticeStatus.QUEUED) continue;
            assignment.setStatus(ReviewPracticeStatus.WITHHELD);
            assignment.setReason(reason);
        }
        review.setStatus(ReviewStatus.CANCELLED);
        review.setStopReason(ReviewStopReason.REPLACED);
        review.setCompletedAt(now);
        return true;
    }
}
