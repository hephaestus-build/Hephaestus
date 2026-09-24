package de.tum.cit.aet.hephaestus.practices.observation.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.DeliveredFeedbackBinding;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackUsefulness;
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
                description =
                        "What to do — the delivered feedback for this observation (null if nothing was delivered)")
        String deliveredFeedback,

        @Nullable
        @Schema(
                description = "The next step the review wrote about this observation, whether or not the "
                        + "feedback carrying it was delivered (null when it wrote none)")
        String nextStep,

        @Nullable
        @Schema(
                description = "The newest delivered feedback that carried this observation to the developer; "
                        + "the handle for responding to it (null when none was delivered)")
        UUID feedbackId,

        @Nullable @Schema(description = "The developer's usefulness response to that feedback")
        FeedbackUsefulness feedbackUsefulness,

        @Nullable @Schema(description = "The developer's resolution response to that feedback")
        FeedbackResolution feedbackResolution,

        @Nullable @Schema(description = "The developer's comment on that feedback")
        String feedbackResponseComment,

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

    public static ObservationDetailDTO from(
            Observation observation,
            @Nullable String deliveredFeedback,
            @Nullable String nextStep,
            @Nullable DeliveredFeedbackBinding feedback,
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
                nextStep,
                feedback == null ? null : feedback.getFeedbackId(),
                feedback == null || feedback.getResponseUsefulness() == null
                        ? null
                        : FeedbackUsefulness.valueOf(feedback.getResponseUsefulness()),
                feedback == null || feedback.getResponseResolution() == null
                        ? null
                        : FeedbackResolution.valueOf(feedback.getResponseResolution()),
                feedback == null ? null : feedback.getResponseComment(),
                observation.getRecurrenceKey(),
                ReviewClaimCurrentness.of(observation.getPracticeRevision(), practice, observation.getSupersededAt()),
                observation.getOrigin(),
                artifactUrl,
                observation.getObservedAt());
    }
}
