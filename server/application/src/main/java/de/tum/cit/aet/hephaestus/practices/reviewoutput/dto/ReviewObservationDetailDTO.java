package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationAnswersDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationEvidenceDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
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

        @NonNull ReviewedWorkRefDTO reviewedWork,

        @Schema(description = "Whose work the observation is about; null when the identity is no longer resolvable")
        @Nullable
        ReviewSubjectDTO subject,

        @NonNull String summary,

        @NonNull @Schema(description = "Result of the practice review")
        Outcome outcome,

        @Schema(description = "Severity band (null unless outcome is NOT_MET)") @Nullable
        Severity severity,

        @Nullable ObservationEvidenceDTO evidence,
        @Nullable String evidenceRationale,

        @Nullable
        @Schema(
                description = "The answers the observation was decided from and the deciding rule; null for an "
                        + "observation recorded before reviews answered questions, and whenever evidence is withheld")
        ObservationAnswersDTO answers,

        @Schema(description = "Cross-run locus key; null when continuity is unavailable") @Nullable
        String recurrenceKey,

        @NonNull ReviewClaimCurrentness claimCurrentness,

        @NonNull
        @Schema(
                description = "Every correction by a workspace admin, newest first; the first is in force while it "
                        + "has no restoration")
        List<ObservationInvalidationDTO> invalidations,

        @NonNull
        @Schema(
                description = "The developer's standing disputes of feedback written from this observation, newest"
                        + " first; empty when nothing about it is disputed")
        List<FeedbackDisputeDTO> disputes,

        @NonNull Instant observedAt,

        @NonNull @Schema(description = "Linked feedback, newest first")
        List<ReviewBoundFeedbackDTO> feedback) {

    public static ReviewObservationDetailDTO from(
            Observation observation,
            ReviewedWorkRefDTO reviewedWork,
            @Nullable ReviewSubjectDTO subject,
            List<ReviewBoundFeedbackDTO> feedback,
            List<ObservationInvalidationDTO> invalidations,
            List<FeedbackDisputeDTO> disputes,
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
                reviewedWork,
                subject,
                observation.getSummary(),
                observation.getOutcome(),
                observation.getSeverity(),
                includeEvidence ? ObservationEvidenceDTO.from(observation.getEvidence()) : null,
                observation.getEvidenceRationale(),
                includeEvidence ? ObservationAnswersDTO.from(observation) : null,
                observation.getRecurrenceKey(),
                ReviewClaimCurrentness.of(revision, practice, observation.getSupersededAt()),
                invalidations,
                disputes,
                observation.getObservedAt(),
                feedback);
    }
}
