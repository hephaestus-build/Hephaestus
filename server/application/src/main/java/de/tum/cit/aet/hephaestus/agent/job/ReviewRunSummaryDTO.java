package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunSummaryRow;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewObservationCountsDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A review run with observation and feedback outcome counts")
public record ReviewRunSummaryDTO(
        @NonNull UUID id,
        @NonNull AgentJobStatus status,
        @NonNull ReviewRunOutcome reviewOutcome,

        @Schema(description = DeliveryStatus.DESCRIPTION) @Nullable
        DeliveryStatus resultProcessing,

        @NonNull ReviewRunTargetDTO target,
        @NonNull Instant createdAt,
        @NonNull ReviewObservationCountsDTO observations,
        @NonNull ReviewFeedbackCountsDTO feedback) {
    static ReviewRunSummaryDTO from(
            ReviewRunSummaryRow review,
            Target target,
            ReviewObservationCountsDTO observations,
            ReviewFeedbackCountsDTO feedback) {
        return new ReviewRunSummaryDTO(
                review.getId(),
                review.getStatus(),
                ReviewRunOutcome.fromRecordedValue(review.getReviewOutcome()),
                review.getDeliveryStatus(),
                ReviewRunTargetDTO.from(target),
                review.getCreatedAt(),
                observations,
                feedback);
    }
}
