package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationEvidenceDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "An observation with evidence and linked feedback")
public record ReviewObservationDetailDTO(
        @NonNull UUID id,
        @NonNull UUID agentJobId,
        @NonNull String practiceSlug,
        @NonNull String practiceName,

        @Schema(description = "Practice group; null when the practice is Unassigned") @Nullable
        ReviewPracticeGroupDTO group,

        @Schema(description = "Criteria revision selected as of job start, when available") @Nullable
        Long practiceRevisionId,

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

        @Nullable ObservationEvidenceDTO evidence,
        @Nullable String evidenceRationale,

        @Schema(description = "Cross-run locus key; null when continuity is unavailable") @Nullable
        String recurrenceKey,

        @NonNull ReviewClaimCurrentness claimCurrentness,
        @NonNull Instant observedAt,

        @NonNull @Schema(description = "Linked feedback, newest first")
        List<ReviewBoundFeedbackDTO> feedback) {
    public static ReviewObservationDetailDTO from(
            Observation observation,
            ReviewArtifactDTO artifact,
            @Nullable ReviewSubjectDTO subject,
            List<ReviewBoundFeedbackDTO> feedback,
            boolean includeEvidence) {
        var practice = observation.getPractice();
        var revision = observation.getPracticeRevision();
        return new ReviewObservationDetailDTO(
                observation.getId(),
                observation.getAgentJobId(),
                practice.getSlug(),
                practice.getName(),
                practice.getGroup() == null ? null : ReviewPracticeGroupDTO.from(practice.getGroup()),
                revision == null ? null : revision.getId(),
                artifact,
                subject,
                observation.getSummary(),
                observation.getAssessmentStatus(),
                observation.getPresence(),
                observation.getAssessment(),
                observation.getSeverity(),
                includeEvidence ? ObservationEvidenceDTO.from(observation.getEvidence()) : null,
                observation.getEvidenceRationale(),
                observation.getRecurrenceKey(),
                ReviewClaimCurrentness.of(revision, practice),
                observation.getObservedAt(),
                feedback);
    }
}
