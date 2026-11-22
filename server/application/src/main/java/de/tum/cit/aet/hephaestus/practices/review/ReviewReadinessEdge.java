package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultFreshness;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWithholdings;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Steps 1 to 3 of an item's readiness edge: of the practices that an event review's occasions select, those that the
 * review measures now, given the item's earlier reviews and the currentness of its open results. A review with an
 * active or unsettled job is still in progress ({@code REVIEW_IN_PROGRESS}): it holds the edge for the practices that
 * it measures, and it does not close until that job settles.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewReadinessEdge {
    private final ReviewItemRepository memberships;
    private final OffPracticeMilestones offMilestones;
    private final Clock clock;
    private final ItemEventService itemEvents;
    private final PracticeItemOptions itemOptions;
    private final ResultRepository results;
    private final ResultFreshness freshness;
    private final ReviewRuntimeQuery runtime;

    /**
     * The currentness of the item's open results, for an event review whose occasions a continuing result can skip. A
     * result that the locked step finds but this read did not judge is not current, so its practice is measured.
     */
    Map<UUID, Currentness> currentness(ReviewRequest request, ItemSnapshot pinned) {
        if (request.origin() != ReviewOrigin.EVENT
                || request.occasions().stream()
                        .anyMatch(name -> itemOptions.occasion(pinned.item().kind(), name) == OccasionKind.MILESTONE))
            return Map.of();
        var claims = results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(request.workspaceId(), request.itemId());
        return freshness.states(request.workspaceId(), claims);
    }

    /**
     * Of the practices the occasions select, those to measure now. Occasions that all continue leave out a practice in
     * feedback mode Off that reviews at a milestone: it waits for that milestone and never follows a newer head. One
     * that this version still owes, such as for its checks or a spent deadline, finishes here. A practice that an
     * earlier review of the item still measures (queued, or running with an unsettled job) holds the whole window until
     * that review ends, so its occasions are never lost. An event review that never started holds a window of
     * occasions that join it whatever it lists, so that its claim replaces it instead of starting a sandbox on work
     * that moved. A continuing occasion leaves out every practice whose result is current; a milestone measures them
     * again.
     */
    List<Practice> pending(
            ItemSnapshot pinned,
            Set<ItemEventName> occasions,
            List<Practice> gated,
            Map<UUID, Currentness> currentness) {
        long workspaceId = pinned.item().workspaceId();
        long itemId = pinned.item().itemId();
        var kind = pinned.item().kind();
        var waiting = new HashSet<Long>();
        if (offMilestones.continuing(kind, occasions)) {
            waiting.addAll(offMilestones.waitingForAMilestone(workspaceId, kind, gated));
            waiting.removeAll(memberships.owedAtVersion(
                    workspaceId, itemId, pinned.version().value(), ReviewWithholdings.CARRIED));
        }
        var selected = gated.stream().filter(p -> !waiting.contains(p.getId())).toList();
        var selectedIds = selected.stream().map(Practice::getId).collect(Collectors.toSet());
        // Starting a review commits before its job is enqueued, so a running review with no job yet still measures.
        // One that never got a job stops holding the window one cap after it started.
        Instant enqueuedBy = clock.instant().minus(itemEvents.quietWindowCap());
        // An event review that never started holds a window whatever it lists: its claim replaces it while this
        // occasion waits, so no sandbox starts on work that moved. Only an occasion that joins a window can replace it.
        boolean replaceable = occasions.stream()
                .allMatch(name -> itemOptions.occasion(kind, name).joinsWindow());
        boolean measured = memberships.listedPractices(workspaceId, itemId).stream()
                .anyMatch(listed -> (replaceable
                                && listed.getStatus().equals(ReviewStatus.QUEUED.name())
                                && listed.getStartedAt() == null
                                && listed.getOrigin().equals(ReviewOrigin.EVENT.name()))
                        || (selectedIds.contains(listed.getPracticeId())
                                && (listed.getStatus().equals(ReviewStatus.QUEUED.name())
                                        || hasUnsettledInvocation(workspaceId, listed.getReviewId())
                                        || listed.getStartedAt() == null
                                        || listed.getStartedAt().isAfter(enqueuedBy))));
        if (measured)
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.REQUEST_CONFLICT,
                    "An earlier review of this item still measures a selected practice.",
                    ReviewRequestReason.REVIEW_IN_PROGRESS);
        if (selected.isEmpty() && !waiting.isEmpty())
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "Every selected practice has feedback mode Off and waits for a milestone of the work.",
                    ReviewRequestReason.FEEDBACK_MODE_OFF);
        if (occasions.stream().anyMatch(name -> itemOptions.occasion(kind, name) == OccasionKind.MILESTONE))
            return selected;
        var current = results.findByWorkspaceIdAndItemIdAndEndedAtIsNull(workspaceId, itemId).stream()
                .filter(result -> currentness.get(result.getId()) == Currentness.CURRENT)
                .map(Result::getPracticeVersionId)
                .collect(Collectors.toSet());
        var pending = selected.stream()
                .filter(p -> !current.contains(p.getCurrentVersion().getId()))
                .toList();
        if (pending.isEmpty())
            throw new ReviewRequestRejected(
                    ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                    "Every selected practice already has a current result.",
                    ReviewRequestReason.ALREADY_CURRENT);
        return pending;
    }

    boolean hasUnsettledInvocation(long workspaceId, UUID reviewId) {
        return !runtime.activeJobIds(workspaceId, reviewId).isEmpty()
                || !runtime.unsettledJobIds(workspaceId, reviewId).isEmpty();
    }
}
