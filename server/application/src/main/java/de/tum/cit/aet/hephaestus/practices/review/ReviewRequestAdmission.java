package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admits a review request against its workspace, or returns the review that an earlier request with the same request
 * key or event already recorded. It refuses a request with a conflicting body, one that the request policy, the
 * workspace state, the gate, an active trial or scan, or the budget refuses.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewRequestAdmission {
    private final ReviewRepository reviews;
    private final ReviewRequestPolicy policy;
    private final ReviewGate gate;
    private final PracticeTrialProperties trials;
    private final ReviewBudget budget;
    private final ProductItemQuery items;

    sealed interface Admission {
        record Replay(UUID reviewId) implements Admission {}

        record Admitted(ItemRef item) implements Admission {}
    }

    Admission admit(ReviewRequest request, ReviewWorkspace workspace) {
        if (request.requestKey() != null) {
            var existing = reviews.findByWorkspaceIdAndRequestKey(request.workspaceId(), request.requestKey());
            if (existing.isPresent()) {
                if (!Objects.equals(existing.orElseThrow().getRequestDigest(), request.requestDigest()))
                    throw replayConflict(existing.orElseThrow());
                return new Admission.Replay(existing.orElseThrow().getId());
            }
        }
        if (request.eventId() != null) {
            var prior = reviews.findByWorkspaceIdAndEventId(request.workspaceId(), request.eventId());
            if (prior.isPresent())
                return new Admission.Replay(prior.orElseThrow().getId());
        }
        if (request.origin() == ReviewOrigin.REQUEST && request.kind() == ReviewKind.ITEM)
            policy.refusal(workspace, request.itemId(), Objects.requireNonNull(request.requestedById()))
                    .ifPresent(reason -> {
                        throw new ReviewRequestRefused(reason);
                    });
        if (!workspace.active())
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.WORKSPACE_INACTIVE, "The workspace is inactive.");
        gate.refusal(workspace, request.origin()).ifPresent(skip -> {
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE, skip.reason(), skip.eventReason());
        });
        if (request.origin() == ReviewOrigin.TRIAL
                && reviews.countByWorkspaceIdAndOriginAndStatusIn(
                                request.workspaceId(),
                                ReviewOrigin.TRIAL,
                                List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING))
                        >= trials.maxActive())
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.TRIAL_ACTIVE,
                    "Another trial is still running in this workspace. Try again when it ends.");
        if (request.kind() == ReviewKind.SCAN) {
            var active = reviews.findFirstByWorkspaceIdAndKindAndStatusInOrderByCreatedAtAsc(
                    request.workspaceId(), ReviewKind.SCAN, List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING));
            if (active.isPresent()) {
                var refusal = new ReviewRequestRejected(
                        ReviewRequestRejected.Reason.SCAN_ACTIVE, "A scan is already active in this workspace.");
                refusal.getBody()
                        .setProperty("activeScanId", active.orElseThrow().getId());
                throw refusal;
            }
        }
        if (!budget.canRequest(request.workspaceId(), request.kind(), request.budgetUsd()))
            throw ReviewRequestRejected.budgetUnavailable();
        return new Admission.Admitted(items.require(request.workspaceId(), request.itemId()));
    }

    private static ReviewRequestRejected replayConflict(Review prior) {
        var refusal = new ReviewRequestRejected(
                ReviewRequestRejected.Reason.REQUEST_CONFLICT, "This request key has a different body.");
        if (prior.getKind() == ReviewKind.SCAN) refusal.getBody().setProperty("scanId", prior.getId());
        return refusal;
    }
}
