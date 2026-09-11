package de.tum.cit.aet.hephaestus.practices.observation.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Detail-view DTO for a single practice observation. Includes delivered feedback, evidence rationale,
 * and structured evidence that are omitted from the list view.
 *
 * <p>Intentionally omits internal fields: {@code agentJobId}, {@code occurrenceKey},
 * and raw {@code aboutUserId}.
 */
@Schema(description = "Full practice observation detail including delivered feedback and evidence")
public record ObservationDetailDTO(
        @NonNull @Schema(description = "Observation ID") UUID id,
        @NonNull @Schema(description = "Practice slug") String practiceSlug,
        @NonNull @Schema(description = "Practice name") String practiceName,

        @NonNull @Schema(description = "Artifact type (e.g. PULL_REQUEST)")
        ArtifactKind artifactKind,

        @NonNull @Schema(description = "Artifact entity ID") Long artifactId,

        @NonNull @Schema(description = "Observation summary")
        String summary,

        @NonNull AssessmentStatus assessmentStatus,

        @Nullable @Schema(description = "PRESENT or ABSENT only when ASSESSED")
        Presence presence,

        @Nullable @Schema(description = "Assessment: GOOD or BAD; null unless assessmentStatus is ASSESSED")
        Assessment assessment,

        @Nullable @Schema(description = "Severity level (null unless assessment is BAD)")
        Severity severity,

        @Nullable ObservationEvidenceDTO evidence,

        @Nullable @Schema(description = "Evidence-based rationale for the observation")
        String evidenceRationale,

        @Nullable
        @Schema(
                description =
                        "What to do — the delivered feedback for this observation (null if nothing was delivered)")
        String deliveredFeedback,

        @NonNull ReviewClaimCurrentness claimCurrentness,

        @NonNull @Schema(description = "What occasioned the measurement; never mix origins in one trend line")
        ObservationOrigin origin,

        @Nullable
        @Schema(description = "Link to the reviewed artifact on its platform (null when it cannot be resolved)")
        String artifactUrl,

        @NonNull @Schema(description = "When the observation was made")
        Instant observedAt) {
    public static ObservationDetailDTO from(
            Observation observation,
            @Nullable String deliveredFeedback,
            @Nullable String artifactUrl,
            boolean includeEvidence) {
        var practice = observation.getPractice();
        return new ObservationDetailDTO(
                observation.getId(),
                practice.getSlug(),
                practice.getName(),
                observation.getArtifactKind(),
                observation.getArtifactId(),
                observation.getSummary(),
                observation.getAssessmentStatus(),
                observation.getPresence(),
                observation.getAssessment(),
                observation.getSeverity(),
                includeEvidence ? ObservationEvidenceDTO.from(observation.getEvidence()) : null,
                observation.getEvidenceRationale(),
                deliveredFeedback,
                ReviewClaimCurrentness.of(observation.getPracticeRevision(), practice),
                observation.getOrigin(),
                artifactUrl,
                observation.getObservedAt());
    }
}
