package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompleted;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWithholdings;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** The end of a review and its children, inside the caller's locked transaction. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ReviewClosure {
    private final ReviewItemRepository memberships;
    private final ReviewPracticeRepository assignments;
    private final ScanReportService reports;
    private final ReviewOutcomeMeters outcomes;
    private final ApplicationEventPublisher events;
    private final ProductItemQuery items;
    private final ItemEventService itemEvents;
    private final ReviewRepository reviews;
    private final PracticeRepository practices;
    private final OffPracticeMilestones offMilestones;
    private final ItemIndexService itemLocks;
    private final Clock clock;

    void close(Review review, ReviewStatus status, @Nullable ReviewStopReason reason, Instant at) {
        review.end(status, reason, at);
        closeMembers(review, at);
        long workspace = review.getWorkspaceId();
        var id = review.getId();
        if (review.getKind() == ReviewKind.SCAN) reports.freeze(workspace, id);
        // A trial is no production review: it has its own signal and stays out of the review outcome meters.
        if (review.getOrigin() == ReviewOrigin.TRIAL) outcomes.trialEnded(review);
        else outcomes.ended(review);
        if ((status == ReviewStatus.COMPLETE || status == ReviewStatus.PARTIAL)
                && memberships.existsByWorkspaceIdAndReviewIdAndStateInAndResultIdIsNotNull(
                        workspace, id, List.of(ReviewItemState.JUDGED, ReviewItemState.REUSED)))
            events.publishEvent(new ReviewCompleted(id, workspace));
        if (review.getChecks().has(ReviewServiceImpl.COMMENT_ANSWER))
            events.publishEvent(new CommentRequestedReviewEnded(id, workspace));
        followUp(review);
    }

    /**
     * Ends a queued event review that newer occasions of its item replace before it starts. A practice that those
     * occasions leave out until a milestone is not replaced, since no newer review measures it: its own sources moved,
     * and the next readiness edge that it is owed at, or the next milestone, selects it again. When that holds for every
     * practice, nothing replaces the review either.
     */
    void replace(Review review, ItemKind kind, Collection<ItemEventName> newer, Instant at) {
        long workspace = review.getWorkspaceId();
        var stop = ReviewStopReason.REPLACED;
        if (offMilestones.continuing(kind, newer)) {
            var listed =
                    memberships
                            .findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspace, review.getId())
                            .stream()
                            .filter(member -> member.getState() == ReviewItemState.LISTED)
                            .toList();
            var waiting = offMilestones.waitingForAMilestone(
                    workspace,
                    kind,
                    practices.findByWorkspaceIdAndIdIn(
                            workspace,
                            listed.stream()
                                    .map(member -> member.getPracticeId())
                                    .collect(Collectors.toSet())));
            for (var member : listed)
                if (waiting.contains(member.getPracticeId()))
                    member.withhold(
                            ReviewStopReason.SOURCES_MOVED.name(),
                            "A source that this practice reads changed before it started.",
                            at);
            for (var assignment : assignments.lockAssignments(workspace, review.getId()))
                if (assignment.getStatus() == ReviewPracticeStatus.QUEUED
                        && waiting.contains(assignment.getPracticeId()))
                    assignment.withhold(ReviewStopReason.SOURCES_MOVED.name());
            if (!listed.isEmpty() && listed.stream().allMatch(member -> waiting.contains(member.getPracticeId())))
                stop = ReviewStopReason.SOURCES_MOVED;
        }
        close(review, ReviewStatus.CANCELLED, stop, at);
    }

    /**
     * The last review of a version to end, with no occasion of that version still waiting, asks for one follow-up
     * occasion of its item at that version when the version still owes practices: their newest review withheld them
     * because the sources moved, they never ran, or a newer review took them over and ended without measuring them. No
     * provider event would measure them before the work changes. A cancellation decides nothing about a practice, so
     * the review before it does. The occasion selects nothing itself, so its readiness edge measures only the carried
     * practices, and it reads the newest event occasion of the version, under that occasion's expiry. An occasion is
     * unique per item, name and version, so a version gets one follow-up: a practice that does not fit again stays
     * carried until a real event. A version with no event review, or whose work moved on, asks for none.
     */
    private void followUp(Review review) {
        var version = review.getItemVersion();
        // A trial measures nothing for the work, so it owes nothing and decides no follow-up.
        if (review.getKind() != ReviewKind.ITEM || review.getOrigin() == ReviewOrigin.TRIAL || version == null) return;
        long workspace = review.getWorkspaceId();
        long itemId = review.getItemId();
        // The item lock orders the ends of the version's reviews, so exactly the last one sees that none is open.
        itemLocks.lock(workspace, itemId);
        reviews.flush();
        // A review still open, or an occasion still waiting, at this version ends later and decides then.
        if (reviews.existsOtherOpenReviewAtVersion(workspace, itemId, version, review.getId())
                || itemEvents.open(workspace, itemId).stream().anyMatch(open -> version.equals(open.itemVersion()))
                || memberships
                        .owedAtVersion(workspace, itemId, version, ReviewWithholdings.FOLLOW_UP)
                        .isEmpty()) return;
        var occasion =
                reviews.newestEventOccasionAtVersion(workspace, itemId, version).orElse(null);
        if (occasion == null
                || !(items.currentWorkVersion(workspace, itemId)
                        instanceof ProductItemQuery.CurrentWorkVersion.Known(String current))
                || !current.equals(version)) return;
        var item = items.find(workspace, itemId).orElse(null);
        if (item == null) return;
        itemEvents.record(new ItemEventInput(
                workspace,
                item.itemId(),
                ItemEventName.carried(item.kind()),
                version,
                null,
                clock.instant(),
                FoundBy.SCHEDULE,
                null,
                null,
                null,
                ItemEventService.carriedFrom(occasion)));
    }

    /**
     * Ends what the closed review still holds open. A practice that nothing measured ends withheld for the reason the
     * review stopped, however far its capture got, and never done. A scan keeps its listed files: its report counts them
     * as not reached.
     */
    private void closeMembers(Review review, Instant at) {
        long workspaceId = review.getWorkspaceId();
        var reason = ReviewWithholdings.unmeasured(
                        Objects.requireNonNullElse(review.getStopReason(), ReviewStopReason.EXECUTION_INCOMPLETE))
                .name();
        var unmeasured = new HashSet<Long>();
        if (review.getKind() == ReviewKind.ITEM)
            for (var member : memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                    workspaceId, review.getId())) {
                if (member.getState() != ReviewItemState.LISTED) continue;
                unmeasured.add(member.getPracticeId());
                member.withhold(
                        reason,
                        review.getStopReason() == ReviewStopReason.REPLACED
                                ? "A newer update of the work replaced this review before it started."
                                : "The review ended before this practice was measured.",
                        at);
            }
        for (var assignment : assignments.lockAssignments(workspaceId, review.getId())) {
            if (assignment.getStatus() == ReviewPracticeStatus.QUEUED
                    || unmeasured.contains(assignment.getPracticeId())) {
                assignment.withhold(reason);
            } else if (assignment.getStatus() != ReviewPracticeStatus.WITHHELD
                    && assignment.getStatus() != ReviewPracticeStatus.FAILED) {
                assignment.finish();
            }
        }
    }
}
