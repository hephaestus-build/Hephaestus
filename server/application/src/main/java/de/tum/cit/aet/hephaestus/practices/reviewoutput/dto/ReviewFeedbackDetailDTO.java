package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.dto.FeedbackApprovalDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import de.tum.cit.aet.hephaestus.practices.trace.dto.DeliveryPolicyTraceDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Full feedback detail including the stored composed body")
public record ReviewFeedbackDetailDTO(
        @NonNull UUID id,
        @NonNull UUID agentJobId,

        @Schema(description = "Reviewed work the feedback targets; null when it is unanchored") @Nullable
        ReviewedWorkRefDTO reviewedWork,

        @Schema(description = "Who the feedback is addressed to; null when the identity is no longer resolvable")
        @Nullable
        ReviewSubjectDTO recipient,

        @Schema(description = "Whose work the feedback addresses; may equal the recipient") @Nullable
        ReviewSubjectDTO subject,

        @NonNull FeedbackChannel channel,
        @NonNull FeedbackDeliveryState deliveryState,

        @Schema(description = "Why delivery stopped; set on withheld or terminally partial feedback") @Nullable
        FeedbackSuppressionReason suppressionReason,

        @Schema(description = "The feedback this one replaced; null on a first delivery") @Nullable
        UUID replacesId,

        @Schema(description = "Cross-run continuity key tying successive deliveries together") @Nullable
        String threadKey,

        @NonNull Instant createdAt,

        @Schema(description = "When the feedback was placed; null if it was not delivered") @Nullable
        Instant deliveredAt,

        @Schema(
                description = "Stored composed body; null when none was produced, and always null on the IN_APP "
                        + "and IN_CHAT channels — neither the developer's practice pages nor the mentor's "
                        + "prepared context is readable by an operator")
        @Nullable
        String body,

        @NonNull @Schema(description = "Source observations in render order")
        List<ReviewBoundObservationDTO> observations,

        @NonNull @Schema(description = "Recorded placements; empty when none")
        List<ReviewPlacementDTO> placements,

        @Schema(description = "Reviewed source revision for an immutable approval package") @Nullable
        String reviewedRevision,

        @NonNull @Schema(description = "Exact ordered summary and inline messages covered by the approval decision")
        List<ReviewProposedPlacementDTO> proposedPlacements,

        @Schema(description = "Immutable human decision for this proposal, when one has been made") @Nullable
        FeedbackApprovalDTO approval,

        @NonNull @Schema(description = "Ordered delivery-policy evaluations for this feedback's review")
        List<DeliveryPolicyTraceDTO> deliveryPolicy) {
    public static ReviewFeedbackDetailDTO from(
            Feedback feedback,
            @Nullable ReviewedWorkRefDTO reviewedWork,
            @Nullable ReviewSubjectDTO recipient,
            @Nullable ReviewSubjectDTO subject,
            List<ReviewBoundObservationDTO> observations,
            List<ReviewPlacementDTO> placements,
            @Nullable FeedbackApprovalDTO approval,
            List<DeliveryPolicyTraceDTO> deliveryPolicy,
            boolean bodyVisible) {
        return new ReviewFeedbackDetailDTO(
                feedback.getId(),
                feedback.getAgentJobId(),
                reviewedWork,
                recipient,
                subject,
                feedback.getChannel(),
                feedback.getDeliveryState(),
                feedback.getSuppressionReason(),
                feedback.getReplacesId(),
                feedback.getThreadKey(),
                feedback.getCreatedAt(),
                feedback.getDeliveredAt(),
                bodyVisible ? feedback.getBody() : null,
                observations,
                placements,
                feedback.getReviewedRevision(),
                bodyVisible
                        ? feedback.getProposedPlacements().stream()
                                .map(ReviewProposedPlacementDTO::from)
                                .toList()
                        : List.of(),
                approval,
                deliveryPolicy);
    }
}
