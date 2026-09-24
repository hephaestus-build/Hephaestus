package de.tum.cit.aet.hephaestus.practices.observation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Per-practice aggregation of observations for a developer (ADR 0022). Used by dashboard practice cards
 * to display strength/problem counts and last activity.
 */
@Schema(description = "Per-practice observation summary for a developer")
public record DeveloperPracticeSummaryDTO(
        @NonNull @Schema(description = "Practice slug") String practiceSlug,
        @NonNull @Schema(description = "Practice name") String practiceName,

        @NonNull @Schema(description = "Total number of observations")
        Long totalObservations,

        @NonNull @Schema(description = "Number of positive outcomes")
        Long positiveCount,

        @NonNull @Schema(description = "Number of negative outcomes")
        Long negativeCount,

        @Nullable @Schema(description = "Timestamp of most recent observation")
        Instant lastObservedAt) {
    public static DeveloperPracticeSummaryDTO from(DeveloperPracticeSummaryProjection p) {
        return new DeveloperPracticeSummaryDTO(
                p.getPracticeSlug(),
                p.getPracticeName(),
                p.getTotalObservations(),
                p.getPositiveCount(),
                p.getNegativeCount(),
                p.getLastObservedAt());
    }
}
