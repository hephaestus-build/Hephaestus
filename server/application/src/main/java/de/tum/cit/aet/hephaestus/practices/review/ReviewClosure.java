package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompleted;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** The end of a review and its children, inside the caller's locked transaction. */
@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
class ReviewClosure {
    private final ReviewItemRepository memberships;
    private final ReviewPracticeRepository assignments;
    // A worker can replace an item review. Only the server ends a scan and has its report service.
    private final ObjectProvider<ScanReportService> reports;
    private final ReviewOutcomeMeters outcomes;
    private final ApplicationEventPublisher events;

    void close(Review review, ReviewStatus status, @Nullable ReviewStopReason reason, Instant at) {
        review.end(status, reason, at);
        closeMembers(review, at);
        long workspace = review.getWorkspaceId();
        var id = review.getId();
        if (review.getKind() == ReviewKind.SCAN) reports.getObject().freeze(workspace, id);
        outcomes.ended(review);
        if ((status == ReviewStatus.COMPLETE || status == ReviewStatus.PARTIAL)
                && memberships.existsByWorkspaceIdAndReviewIdAndStateInAndResultIdIsNotNull(
                        workspace, id, List.of(ReviewItemState.JUDGED, ReviewItemState.REUSED)))
            events.publishEvent(new ReviewCompleted(id, workspace));
        if (review.getChecks().has(ReviewServiceImpl.COMMENT_ANSWER))
            events.publishEvent(new CommentRequestedReviewEnded(id, workspace));
    }

    /**
     * Ends what the closed review still holds open. A practice that nothing measured ends withheld for the reason the
     * review stopped, however far its capture got, and never done. A scan keeps its listed files: its report counts them
     * as not reached.
     */
    private void closeMembers(Review review, Instant at) {
        long workspaceId = review.getWorkspaceId();
        var reason = Objects.requireNonNullElse(review.getStopReason(), ReviewStopReason.EXECUTION_INCOMPLETE)
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
                assignment.setStatus(ReviewPracticeStatus.WITHHELD);
                assignment.setReason(reason);
            } else if (assignment.getStatus() != ReviewPracticeStatus.WITHHELD
                    && assignment.getStatus() != ReviewPracticeStatus.FAILED) {
                assignment.setStatus(ReviewPracticeStatus.DONE);
            }
        }
    }
}
