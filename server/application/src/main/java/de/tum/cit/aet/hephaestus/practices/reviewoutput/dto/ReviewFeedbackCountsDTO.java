package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.FeedbackStateCounts;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;

@NamedInterface("review-output")
@Schema(description = "Counts of feedback, one per delivery state")
public record ReviewFeedbackCountsDTO(
        @NonNull @Schema(description = "AWAITING_APPROVAL: waiting for an admin to approve or reject it")
        Long awaitingApproval,

        @NonNull @Schema(description = "PREPARED: ready to deliver and not delivered yet")
        Long prepared,

        @NonNull
        @Schema(
                description = "PARTIALLY_DELIVERED: some of its placements delivered, the rest still to deliver or"
                        + " suppressed")
        Long partiallyDelivered,

        @NonNull @Schema(description = "PARTIALLY_FAILED: delivery failed after some of its placements were delivered")
        Long partiallyFailed,

        @NonNull @Schema(description = "DELIVERED: delivered where it was meant to appear")
        Long delivered,

        @NonNull @Schema(description = "SUPERSEDED: replaced by newer feedback")
        Long superseded,

        @NonNull @Schema(description = "SUPPRESSED: Hephaestus refused to deliver it, with a suppression reason")
        Long suppressed,

        @NonNull @Schema(description = "FAILED: delivery failed")
        Long failed,

        @NonNull @Schema(description = "DISCARDED: an admin rejected it instead of approving it; it is never delivered")
        Long discarded,

        @NonNull
        @Schema(
                description = "UNCONFIRMED: a conversation linked it with no record that it was shown, so it counts"
                        + " as neither delivered nor suppressed")
        Long unconfirmed) {
    public static ReviewFeedbackCountsDTO empty() {
        return new ReviewFeedbackCountsDTO(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public static ReviewFeedbackCountsDTO from(@Nullable FeedbackStateCounts counts) {
        return counts == null
                ? empty()
                : new ReviewFeedbackCountsDTO(
                        counts.getAwaitingApproval(),
                        counts.getPrepared(),
                        counts.getPartiallyDelivered(),
                        counts.getPartiallyFailed(),
                        counts.getDelivered(),
                        counts.getSuperseded(),
                        counts.getSuppressed(),
                        counts.getFailed(),
                        counts.getDiscarded(),
                        counts.getUnconfirmed());
    }

    public ReviewFeedbackCountsDTO plus(ReviewFeedbackCountsDTO other) {
        return new ReviewFeedbackCountsDTO(
                awaitingApproval + other.awaitingApproval,
                prepared + other.prepared,
                partiallyDelivered + other.partiallyDelivered,
                partiallyFailed + other.partiallyFailed,
                delivered + other.delivered,
                superseded + other.superseded,
                suppressed + other.suppressed,
                failed + other.failed,
                discarded + other.discarded,
                unconfirmed + other.unconfirmed);
    }
}
