package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewFreshness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewStartService implements ReviewStart {
    private final ReviewRepository reviews;
    private final ProductItemQuery items;
    private final PracticeItemOptions itemOptions;
    private final ItemEventService events;
    private final Clock clock;
    private final FeedbackRepair repairs;
    private final ReviewFreshness membership;
    private final ReviewClosure closure;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean start(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        return startLocked(review);
    }

    private boolean startLocked(Review review) {
        return review.start(clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requeue(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        review.requeue();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean compose(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (!review.getStatus().isTerminal()) return startLocked(review);
        return (review.getStatus() == ReviewStatus.COMPLETE || review.getStatus() == ReviewStatus.PARTIAL)
                && !review.isCancelRequested()
                && repairs.pending(workspaceId, reviewId);
    }

    // A person's request stays. Only a newer waiting occasion with changed readable sources replaces an event review.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean replaceByNewerOccasion(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        if (review.getStatus() != ReviewStatus.QUEUED
                || review.getChecks().has("startedAt")
                || review.getKind() != ReviewKind.ITEM
                || review.getOrigin() != ReviewOrigin.EVENT
                || review.isCancelRequested()) return false;
        var kind = items.find(workspaceId, review.getItemId()).map(ItemRef::kind);
        if (kind.isEmpty()) return false;
        boolean moved = events.open(workspaceId, review.getItemId()).stream()
                .anyMatch(waiting -> waiting.itemVersion() != null
                        && waiting.occurredAt().isAfter(review.getOrderedAt())
                        && itemOptions
                                .occasion(kind.orElseThrow(), waiting.name())
                                .joinsWindow());
        if (!moved || membership.capturedCurrentness(workspaceId, reviewId) != Currentness.STALE) return false;
        closure.close(review, ReviewStatus.CANCELLED, ReviewStopReason.REPLACED, clock.instant());
        return true;
    }
}
