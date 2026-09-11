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
 * List-view DTO for practice observations. Omits large text fields (delivered feedback, evidence rationale)
 * and internal fields (agentJobId, occurrenceKey, evidence) to keep payloads small.
 */
@Schema(description = "Practice observation summary for list views")
public record ObservationListDTO(
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

        @NonNull ReviewClaimCurrentness claimCurrentness,

        @NonNull @Schema(description = "What occasioned the measurement; never mix origins in one trend line")
        ObservationOrigin origin,

        @NonNull @Schema(description = "When the observation was made")
        Instant observedAt) {
    /**
     * Maps a {@link Observation} entity (with eagerly fetched practice) to a list DTO.
     */
    public static ObservationListDTO from(Observation observation) {
        var practice = observation.getPractice();
        return new ObservationListDTO(
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
                ReviewClaimCurrentness.of(observation.getPracticeRevision(), practice),
                observation.getOrigin(),
                observation.getObservedAt());
    }
}
