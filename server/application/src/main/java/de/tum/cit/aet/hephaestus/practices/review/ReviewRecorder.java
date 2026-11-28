package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAudience;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequested;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewVersionKind;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a planned review in the locked step of its request: the review, its practice assignments with the source
 * fingerprints they queue, and for an item review its memberships. Then it closes a review that withholds every
 * membership, or publishes {@link ReviewRequested} for any other.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewRecorder {
    private final ReviewRepository reviews;
    private final ReviewPracticeRepository assignments;
    private final ReviewItemRepository memberships;
    private final JsonMapper json;
    private final Clock clock;
    private final ReviewClosure closure;
    private final ApplicationEventPublisher events;
    private final ReviewSourceReads sourceReads;

    UUID record(
            ReviewRequest request,
            ReviewWorkspace workspace,
            ItemSnapshot pinned,
            ReviewPlanner.Plan plan,
            ReviewSourceReads.UnlockedRead unlocked) {
        var now = clock.instant();
        var ordered = plan.occurredAt() != null ? plan.occurredAt() : now;
        var planned = plan.planned();
        JsonNode queuedSources = null;
        if (request.kind() == ReviewKind.ITEM) {
            var kinds = sourceReads.queuedKinds(pinned, planned);
            queuedSources = sourceReads.queuedSources(kinds, ReviewSourceReads.fingerprints(kinds, unlocked));
        }
        var review = reviews.save(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(request.workspaceId())
                .itemId(request.itemId())
                .kind(request.kind())
                .itemVersion(pinned.version().value())
                .baseVersion(pinned.version().baseVersion())
                .origin(request.origin())
                .setupId(request.setupId())
                .versionKind(ReviewVersionKind.ofOlder(request.kind()))
                .audience(
                        request.audience() != null
                                ? request.audience()
                                : ReviewAudience.ofOlder(request.kind(), request.origin()))
                .audienceTeamId(request.audienceTeamId())
                .eventId(request.eventId())
                .batchId(request.batchId())
                .requestedById(request.requestedById())
                .budgetUsd(request.budgetUsd())
                .requestKey(request.requestKey())
                .requestDigest(request.requestDigest())
                .rolloutRevision(workspace.reviewSettings().rolloutRevision())
                .orderedAt(ordered)
                .createdAt(now)
                .checks(plan.checks())
                .build());
        for (var selected : planned) {
            var practice = selected.practice();
            var version = selected.version();
            var assignment = assignments.save(ReviewPractice.builder()
                    .reviewId(review.getId())
                    .workspaceId(request.workspaceId())
                    .practiceId(practice.getId())
                    .practiceVersionId(version.getId())
                    .captureDecision(selected.reason() == null ? queuedSources : null)
                    .method(plan.method())
                    .build());
            if (request.kind() == ReviewKind.ITEM) {
                var roles = selected.roles();
                var detail = json.createObjectNode();
                if (roles.size() == 1) detail.put("developerId", roles.getFirst());
                else detail.putNull("developerId");
                var reason = selected.reason();
                memberships.save(ReviewItem.builder()
                        .workspaceId(request.workspaceId())
                        .reviewId(review.getId())
                        .practiceId(practice.getId())
                        .itemId(request.itemId())
                        .itemVersion(pinned.version().value())
                        .state(reason == null ? ReviewItemState.LISTED : ReviewItemState.WITHHELD)
                        .reason(reason)
                        .detail(detail)
                        .build());
                assignment.setItemsListed(1);
                assignment.setListingComplete(true);
                if (reason != null) assignment.withhold(reason);
            }
        }
        if (request.kind() == ReviewKind.ITEM
                && memberships
                        .findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                                request.workspaceId(), review.getId())
                        .stream()
                        .allMatch(m -> m.getState() == ReviewItemState.WITHHELD)) {
            closure.close(review, ReviewStatus.PARTIAL, ReviewStopReason.READINESS_REFUSED, now);
        } else events.publishEvent(new ReviewRequested(review.getId(), request.workspaceId()));
        return review.getId();
    }
}
