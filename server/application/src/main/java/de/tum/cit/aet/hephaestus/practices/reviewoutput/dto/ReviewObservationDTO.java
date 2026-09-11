package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ObservationFeedbackDisposition;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.OperatorObservationRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A practice review observation with its linked feedback outcomes")
public record ReviewObservationDTO(
        @NonNull UUID id,
        @NonNull UUID agentJobId,
        @NonNull String practiceSlug,
        @NonNull String practiceName,

        @Schema(description = "Practice group; null when the practice is Unassigned") @Nullable
        ReviewPracticeGroupDTO group,

        @NonNull ReviewArtifactDTO artifact,

        @Schema(description = "Whose work the observation is about; null when the identity is no longer resolvable")
        @Nullable
        ReviewSubjectDTO subject,

        @NonNull String summary,
        @NonNull AssessmentStatus assessmentStatus,
        @Nullable Presence presence,

        @Schema(description = "Assessment: GOOD or BAD (null unless ASSESSED)") @Nullable
        Assessment assessment,

        @Schema(description = "Severity band (null unless assessment is BAD)") @Nullable
        Severity severity,

        @Schema(description = "Cross-run locus key; null when continuity is unavailable") @Nullable
        String recurrenceKey,

        @NonNull
        @Schema(
                description = "What occasioned the measurement. BACKFILL came from a confirmed campaign over work "
                        + "that already existed, so it is not a point on the live trend line.")
        ObservationOrigin origin,

        @NonNull ReviewClaimCurrentness claimCurrentness,
        @NonNull Instant observedAt,

        @NonNull @Schema(description = "Counts of linked feedback by delivery state")
        ReviewFeedbackDispositionDTO feedbackDisposition) {
    public static ReviewObservationDTO from(
            OperatorObservationRow row,
            @Nullable ObservationFeedbackDisposition disposition,
            ReviewArtifactDTO artifact,
            Map<Long, ReviewSubjectDTO> subjects) {
        return new ReviewObservationDTO(
                row.getId(),
                row.getAgentJobId(),
                row.getPracticeSlug(),
                row.getPracticeName(),
                ReviewPracticeGroupDTO.from(
                        row.getGroupSlug(), row.getGroupName(), row.getGroupIcon(), row.getGroupColor()),
                artifact,
                subjects.get(row.getAboutUserId()),
                row.getSummary(),
                row.getAssessmentStatus(),
                row.getPresence(),
                row.getAssessment(),
                row.getSeverity(),
                row.getRecurrenceKey(),
                row.getOrigin(),
                ReviewClaimCurrentness.of(
                        row.getPracticeRevisionFingerprint(), row.getCurrentPracticeRevisionFingerprint()),
                row.getObservedAt(),
                disposition == null
                        ? ReviewFeedbackDispositionDTO.empty()
                        : ReviewFeedbackDispositionDTO.from(disposition));
    }
}
