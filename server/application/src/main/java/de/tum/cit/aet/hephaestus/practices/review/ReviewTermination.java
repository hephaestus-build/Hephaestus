package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCancellationRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWithholdings;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completes and cancels a review under its row lock, inside the transaction of the caller: the terminal status and
 * stop reason that completion closes it with, and the cancellation request that it records and publishes.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewTermination {
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final ReviewItemRepository memberships;
    private final ReviewReadinessEdge readinessEdge;
    private final ReviewClosure closure;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(long workspaceId, UUID reviewId, ReviewCompletion completion) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus().isTerminal()) return;
        if (!completion.status().isTerminal())
            throw new IllegalArgumentException("Completion requires a terminal status.");
        if (readinessEdge.hasUnsettledInvocation(workspaceId, reviewId))
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.REQUEST_CONFLICT, "The active invocation must settle before closure.");
        boolean incomplete = (review.getKind() == ReviewKind.SCAN
                        && (review.getInventoryReceipt() == null
                                || !review.getInventoryReceipt()
                                        .path("complete")
                                        .asBoolean()
                                || assignments
                                        .findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(workspaceId, reviewId)
                                        .stream()
                                        .anyMatch(p -> !Boolean.TRUE.equals(p.getListingComplete()))))
                || memberships.existsByWorkspaceIdAndReviewIdAndStateIn(
                        workspaceId,
                        reviewId,
                        List.of(ReviewItemState.WITHHELD, ReviewItemState.LISTED, ReviewItemState.STAGED));
        boolean cancellationRequested = review.isCancelRequested();
        var status = cancellationRequested
                ? ReviewStatus.CANCELLED
                : completion.status() == ReviewStatus.COMPLETE && incomplete
                        ? ReviewStatus.PARTIAL
                        : completion.status();
        // A cancellation that nobody requested keeps the runtime's recorded reason.
        var reason = status == ReviewStatus.COMPLETE
                ? null
                : status == ReviewStatus.CANCELLED
                        ? cancellationRequested || completion.reason() == null
                                ? ReviewStopReason.CANCELLED
                                : completion.reason()
                        : completion.reason() == null ? unmeasuredReason(review) : completion.reason();
        closure.close(review, status, reason, completion.completedAt());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus().isTerminal()) return false;
        if (!review.requestCancel()) return true;
        events.publishEvent(new ReviewCancellationRequested(reviewId, workspaceId));
        if (!readinessEdge.hasUnsettledInvocation(workspaceId, reviewId))
            closure.close(review, ReviewStatus.CANCELLED, ReviewStopReason.CANCELLED, clock.instant());
        return true;
    }

    /**
     * Why a review whose run ended without a reason of its own left practices unmeasured. When Hephaestus withheld
     * each of them on purpose ({@link ReviewWithholdings#DELIBERATE}), the review was not ready, as selection records
     * when it withholds every practice: {@code REPLACED} when a newer review replaced every one of them,
     * {@code SOURCES_MOVED} when the sources moved for every one of them and not every one was replaced,
     * {@code STALE_ROLLOUT_REVISION} when the review settings changed for every one of them, and
     * {@code READINESS_REFUSED} otherwise. A practice that the run left open, or one that a run or admission withheld
     * for a fault, is execution that did not finish. Measured practices count neither way.
     */
    private ReviewStopReason unmeasuredReason(Review review) {
        if (review.getKind() != ReviewKind.ITEM) return ReviewStopReason.EXECUTION_INCOMPLETE;
        var reasons = new HashSet<@Nullable String>();
        for (var member : memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                review.getWorkspaceId(), review.getId())) {
            if (member.getState() == ReviewItemState.LISTED || member.getState() == ReviewItemState.STAGED)
                return ReviewStopReason.EXECUTION_INCOMPLETE;
            if (member.getState() == ReviewItemState.WITHHELD) reasons.add(member.getReason());
        }
        return unmeasuredReason(reasons);
    }

    /** {@link #unmeasuredReason(Review)} for the reasons of the review's withheld memberships. */
    static ReviewStopReason unmeasuredReason(Set<@Nullable String> withheld) {
        if (withheld.isEmpty()
                || !withheld.stream()
                        .allMatch(reason -> reason != null && ReviewWithholdings.DELIBERATE.contains(reason)))
            return ReviewStopReason.EXECUTION_INCOMPLETE;
        if (withheld.equals(Set.of(ReviewStopReason.REPLACED.name()))) return ReviewStopReason.REPLACED;
        if (Set.of(ReviewStopReason.REPLACED.name(), ReviewStopReason.SOURCES_MOVED.name())
                .containsAll(withheld)) return ReviewStopReason.SOURCES_MOVED;
        if (withheld.equals(Set.of(ReviewWithholdings.STALE_ROLLOUT_REVISION)))
            return ReviewStopReason.STALE_ROLLOUT_REVISION;
        return ReviewStopReason.READINESS_REFUSED;
    }
}
