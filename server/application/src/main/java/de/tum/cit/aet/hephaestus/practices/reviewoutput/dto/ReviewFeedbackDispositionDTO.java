package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ObservationFeedbackDisposition;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "Counts of feedback by delivery state")
public record ReviewFeedbackDispositionDTO(
        @NonNull @Schema(description = "Linked feedback awaiting delivery")
        Long prepared,

        @NonNull @Schema(description = "Linked feedback delivered")
        Long delivered,

        @NonNull @Schema(description = "Linked feedback delivered and later replaced")
        Long superseded,

        @NonNull @Schema(description = "Linked feedback withheld by policy")
        Long suppressed,

        @NonNull @Schema(description = "Linked feedback whose delivery failed")
        Long failed,

        @NonNull @Schema(description = "Linked feedback a conversation linked without a record that it was shown")
        Long unconfirmed) {
    public static ReviewFeedbackDispositionDTO empty() {
        return new ReviewFeedbackDispositionDTO(0L, 0L, 0L, 0L, 0L, 0L);
    }

    public static ReviewFeedbackDispositionDTO from(ObservationFeedbackDisposition row) {
        return new ReviewFeedbackDispositionDTO(
                row.getPrepared(),
                row.getDelivered(),
                row.getSuperseded(),
                row.getSuppressed(),
                row.getFailed(),
                row.getUnconfirmed());
    }
}
