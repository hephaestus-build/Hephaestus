package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunSummaryRow;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackCountsDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A review run with observation and feedback outcome counts")
public record ReviewRunSummaryDTO(
        @NonNull UUID id,
        @NonNull AgentJobStatus status,

        @Schema(description = DeliveryStatus.DESCRIPTION) @Nullable
        DeliveryStatus resultProcessing,

        @NonNull ReviewRunTargetDTO target,
        @NonNull Instant createdAt,
        @NonNull ReviewObservationCountsDTO observations,
        @NonNull ReviewFeedbackCountsDTO feedback) {
    static ReviewRunSummaryDTO from(
            ReviewRunSummaryRow review, ReviewObservationCountsDTO observations, ReviewFeedbackCountsDTO feedback) {
        return new ReviewRunSummaryDTO(
                review.getId(),
                review.getStatus(),
                review.getDeliveryStatus(),
                ReviewRunTargetDTO.from(review),
                review.getCreatedAt(),
                observations,
                feedback);
    }
}
