package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
     * A review that ends owing practices asks for one follow-up occasion of its item at the same version: a moved source
     * or a spent deadline is no provider event, so nothing else would measure them before the work changes. The
     * occasion selects nothing itself, so its readiness edge measures only the carried practices, and it reads its
     * review's own occasion, under that occasion's expiry. An occasion is unique per item, name and version, so a
     * version gets one follow-up: a practice that does not fit again stays carried until a real event. A review with
     * no occasion of its own, a cancelled one, one whose work moved on, or one whose every owed practice a newer review
     * measures asks for none.
     */
    private void followUp(Review review) {
        var version = review.getItemVersion();
        var occasion = review.getEventId();
        if (review.getKind() != ReviewKind.ITEM
                || review.getOrigin() != ReviewOrigin.EVENT
                || occasion == null
                || version == null
                || review.getStatus() == ReviewStatus.CANCELLED) return;
        long workspace = review.getWorkspaceId();
        var owed =
                memberships
                        .findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspace, review.getId())
                        .stream()
                        .filter(member -> member.getState() == ReviewItemState.WITHHELD
                                && ReviewWithholdings.FOLLOW_UP.contains(member.getReason()))
                        .map(member -> member.getPracticeId())
                        .distinct()
                        .toList();
        // A newer review measures an owed practice only if it selected it; one that did not leaves it to this
        // follow-up.
        if (owed.isEmpty()
                || owed.stream()
                        .allMatch(practice -> reviews.existsNewerReviewOfPractice(
                                workspace, review.getItemId(), practice, review.getId(), review.getOrderedAt()))
                || !(items.currentWorkVersion(workspace, review.getItemId())
                        instanceof ProductItemQuery.CurrentWorkVersion.Known(String current))
                || !current.equals(version)) return;
        var item = items.find(workspace, review.getItemId()).orElse(null);
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
