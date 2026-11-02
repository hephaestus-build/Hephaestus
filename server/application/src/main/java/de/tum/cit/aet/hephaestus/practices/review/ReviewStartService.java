package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
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
import tools.jackson.databind.node.JsonNodeFactory;
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean start(long workspaceId, UUID reviewId) {
        var review = reviews.lockByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
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

    // A person's request stays: they asked for this review. An immediate occasion keeps waiting for it, as does one at
    // the same version, which leaves the review's input as it was.
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
                        && !waiting.itemVersion().equals(review.getItemVersion())
                        && waiting.occurredAt().isAfter(review.getOrderedAt())
                        && itemOptions
                                .occasion(kind.orElseThrow(), waiting.name())
                                .joinsWindow());
        if (!moved) return false;
        var now = clock.instant();
        String reason = ReviewStopReason.REPLACED.name();
        for (var member :
                memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspaceId, reviewId)) {
            if (member.getState() != ReviewItemState.LISTED) continue;
            member.setState(ReviewItemState.WITHHELD);
            member.setReason(reason);
            member.setAttemptedAt(now);
            var detail = member.getDetail() instanceof ObjectNode kept
                    ? kept.deepCopy()
                    : JsonNodeFactory.instance.objectNode();
            detail.put("withholdingMessage", "A newer update of the work replaced this review before it started.");
            member.setDetail(detail);
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
