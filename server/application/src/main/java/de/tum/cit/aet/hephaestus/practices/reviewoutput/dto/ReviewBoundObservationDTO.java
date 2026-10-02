package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.BoundObservation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "An observation that contributed to a piece of feedback")
public record ReviewBoundObservationDTO(
        @NonNull UUID observationId,

        @NonNull @Schema(description = "Whether the observation leads the feedback or reinforces it")
        EvidenceRole role,

        @NonNull @Schema(description = "Render order within the feedback (lower renders earlier)")
        Integer ordinal,

        @NonNull String practiceSlug,
        @NonNull String practiceName,

        @Schema(description = "Practice group; null when the practice is Unassigned") @Nullable
        ReviewPracticeGroupDTO group,

        @NonNull String summary,

        @NonNull @Schema(description = "Result of the practice review")
        Outcome outcome,

        @Schema(description = "Severity band (null unless outcome is NOT_MET)") @Nullable
        Severity severity,

        @NonNull ReviewClaimCurrentness claimCurrentness,
        @NonNull Instant observedAt) {

    public static ReviewBoundObservationDTO from(BoundObservation row) {
        return new ReviewBoundObservationDTO(
                row.getObservationId(),
                row.getRole(),
                row.getOrdinal(),
                row.getPracticeSlug(),
                row.getPracticeName(),
                ReviewPracticeGroupDTO.from(
                        row.getGroupSlug(), row.getGroupName(), row.getGroupIcon(), row.getGroupColor()),
                row.getSummary(),
                row.getOutcome(),
                row.getSeverity(),
                ReviewClaimCurrentness.of(
                        row.getPracticeRevisionFingerprint(),
                        row.getCurrentPracticeRevisionFingerprint(),
                        row.getSupersededAt()),
                row.getObservedAt());
    }
}
