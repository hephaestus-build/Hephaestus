package de.tum.cit.aet.hephaestus.practices.observation.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.ObservationFeedbackUnit;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The one observation shape a developer reads: what was observed, why it was noted, what to try next, the
 * evidence behind it and the developer's own response to the feedback that carried it. Served on its own by
 * the observation detail endpoint and per observation by the review-run feed, so a feed row never needs a
 * second request to open.
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

        @Nullable
        @Schema(
                description =
                        "Contextual desirability of the specified behavior: GOOD or BAD; null unless assessmentStatus is ASSESSED")
        Assessment assessment,

        @Nullable @Schema(description = "Severity level (null unless outcome is NEGATIVE)")
        Severity severity,

        @Nullable ObservationEvidenceDTO evidence,

        @Nullable @Schema(description = "Evidence-based rationale for the observation")
        String evidenceRationale,

        @Nullable
        @Schema(
                description = "What to do — the text of the newest feedback unit that said something about this "
                        + "observation to this developer (null if nothing was said)")
        String deliveredFeedback,

        @Nullable
        @Schema(
                description = "The next step the review wrote about this observation, whether or not the "
                        + "feedback carrying it was delivered (null when it wrote none)")
        String nextStep,

        @Nullable
        @Schema(
                description = "The developer's standing answer to the very feedback whose text deliveredFeedback "
                        + "shows, with that unit's id as the handle for responding (null when nothing was said, or "
                        + "when the unit that said it failed to deliver and so cannot be answered)")
        FeedbackResponseDTO feedbackResponse,

        @Nullable @Schema(description = "Cross-run locus key; null when continuity is unavailable")
        String recurrenceKey,

        @NonNull ReviewClaimCurrentness claimCurrentness,

        @NonNull @Schema(description = "What occasioned the measurement; never mix origins in one trend line")
        ObservationOrigin origin,

        @Nullable
        @Schema(description = "Link to the reviewed artifact on its platform (null when it cannot be resolved)")
        String artifactUrl,

        @NonNull @Schema(description = "When the observation was made")
        Instant observedAt) {
    @com.fasterxml.jackson.annotation.JsonProperty("outcome")
    @Schema(
            description = "Derived from presence and contextual behavior assessment; null unless assessed",
            accessMode = Schema.AccessMode.READ_ONLY)
    public @Nullable Outcome getOutcome() {
        return Outcome.of(presence, assessment);
    }

    /**
     * One feedback unit answers both {@code deliveredFeedback} and {@code feedbackResponse}, so the developer
     * always rates the words they just read. A FAILED unit's text is still shown — it was composed and may have
     * reached them on the artifact — but it carries no response handle, because only a DELIVERED unit can be
     * answered.
     */
    public static ObservationDetailDTO from(
            Observation observation,
            @Nullable ObservationFeedbackUnit feedback,
            @Nullable String nextStep,
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
                feedback == null ? null : feedback.getBody(),
                nextStep,
                responseTo(feedback),
                observation.getRecurrenceKey(),
                ReviewClaimCurrentness.of(observation.getPracticeRevision(), practice, observation.getSupersededAt()),
                observation.getOrigin(),
                artifactUrl,
                observation.getObservedAt());
    }

    /** No handle, no response: a unit that failed to deliver cannot be answered, so it carries none. */
    private static @Nullable FeedbackResponseDTO responseTo(@Nullable ObservationFeedbackUnit feedback) {
        if (feedback == null) {
            return null;
        }
        UUID feedbackId = feedback.getFeedbackId();
        return feedbackId == null ? null : FeedbackResponseDTO.from(feedbackId, feedback);
    }
}
