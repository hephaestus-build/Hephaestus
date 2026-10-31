package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueue;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Queues item execution before the request transaction commits. */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@RequiredArgsConstructor
public class ReviewJobEventListener {
    private final ReviewService reviews;
    private final ReviewQueue queue;
    private final ReviewAssignmentQuery assignments;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onReviewRequested(ReviewRequested event) {
        var review = reviews.find(event.workspaceId(), event.reviewId())
                .orElseThrow(() -> new IllegalStateException("The requested review is unavailable"));
        if (review.kind() == ReviewKind.ITEM) {
            if (!reviews.start(review.workspaceId(), review.id())) return;
            var selected = assignments.executable(
                    review.workspaceId(), review.id(), review.item().itemId());
            if (selected.isEmpty()) {
                var now = Instant.now();
                reviews.complete(
                        review.workspaceId(),
                        review.id(),
                        new ReviewCompletion(ReviewStatus.PARTIAL, ReviewStopReason.READINESS_REFUSED, now, now));
                return;
            }
            queue.enqueue(review, review.item().itemId(), selected);
        }
    }
}
