package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.BoundObservation;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
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
        @NonNull AssessmentStatus assessmentStatus,
        @Nullable Presence presence,

        @Schema(description = "Target behaviour: GOOD means desirable, BAD means undesirable (null unless ASSESSED)")
        @Nullable
        Assessment assessment,

        @Schema(description = "Severity band (null unless outcome is NEGATIVE)") @Nullable
        Severity severity,

        @NonNull ReviewClaimCurrentness claimCurrentness,
        @NonNull Instant observedAt) {
    @com.fasterxml.jackson.annotation.JsonProperty("outcome")
    @Schema(
            description = "Derived from presence and target assessment; null unless assessed",
            accessMode = Schema.AccessMode.READ_ONLY)
    public @Nullable Outcome getOutcome() {
        return Outcome.of(presence, assessment);
    }

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
                row.getAssessmentStatus(),
                row.getPresence(),
                row.getAssessment(),
                row.getSeverity(),
                ReviewClaimCurrentness.of(
                        row.getPracticeRevisionFingerprint(), row.getCurrentPracticeRevisionFingerprint()),
                row.getObservedAt());
    }
}
