package de.tum.cit.aet.hephaestus.practices.observation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Per-practice aggregation of current observations for a developer (ADR 0022).
 */
@Schema(description = "Per-practice observation summary for a developer")
public record DeveloperPracticeSummaryDTO(
        @NonNull @Schema(description = "Practice slug") String practiceSlug,
        @NonNull @Schema(description = "Practice name") String practiceName,

        @NonNull @Schema(description = "Total number of observations")
        Long totalObservations,

        @NonNull @Schema(description = "Number of MET observations")
        Long met,

        @NonNull @Schema(description = "Number of NOT_MET observations")
        Long notMet,

        @NonNull Long notApplicable,
        @NonNull Long undetermined,

        @Nullable @Schema(description = "Timestamp of most recent observation")
        Instant lastObservedAt) {
    public static DeveloperPracticeSummaryDTO from(DeveloperPracticeSummaryProjection p) {
        return new DeveloperPracticeSummaryDTO(
                p.getPracticeSlug(),
                p.getPracticeName(),
                p.getTotalObservations(),
                p.getMet(),
                p.getNotMet(),
                p.getNotApplicable(),
                p.getUndetermined(),
                p.getLastObservedAt());
    }
}
