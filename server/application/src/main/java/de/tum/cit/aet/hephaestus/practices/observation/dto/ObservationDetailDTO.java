package de.tum.cit.aet.hephaestus.practices.observation.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.ObservationFeedback;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
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

        @NonNull @Schema(description = "Result of the practice review")
        Outcome outcome,

        @Nullable @Schema(description = "Severity level (null unless outcome is NOT_MET)")
        Severity severity,

        @Nullable ObservationEvidenceDTO evidence,

        @Nullable @Schema(description = "Evidence-based rationale for the observation")
        String evidenceRationale,

        @Nullable
        @Schema(
                description =
                        "The summary body of the newest eligible feedback about this observation to this developer "
                                + "(null when no summary body was recorded, including delivery through inline notes only)")
        String deliveredFeedback,

        @Nullable
        @Schema(
                description = "The next step the review wrote about this observation, whether or not the "
                        + "feedback carrying it was delivered (null when it wrote none)")
        String nextStep,

        @Nullable
        @Schema(
                description =
                        "The developer's standing answer and response handle for the newest eligible feedback about "
                                + "this observation, including inline-only delivery with no summary body (null when no "
                                + "eligible feedback exists or the newest eligible feedback has failed delivery)")
        FeedbackResponseDTO feedbackResponse,

        @Nullable @Schema(description = "Cross-run locus key; null when continuity is unavailable")
        String recurrenceKey,

        @NonNull ReviewClaimCurrentness claimCurrentness,

        @Nullable
        @Schema(
                description = "When a workspace admin marked this observation as incorrect; null while it stands. "
                        + "An invalidated observation counts toward nothing current.")
        Instant invalidatedAt,

        @Nullable @Schema(description = "The admin's reason for the invalidation; null while the observation stands")
        String invalidationReason,

        @NonNull @Schema(description = "What occasioned the measurement; never mix origins in one trend line")
        ObservationOrigin origin,

        @Nullable
        @Schema(description = "Link to the reviewed artifact on its platform (null when it cannot be resolved)")
        String artifactUrl,

        @NonNull @Schema(description = "When the observation was made")
        Instant observedAt) {

    /**
     * One piece of feedback answers both {@code deliveredFeedback} and {@code feedbackResponse}, so the
     * developer answers the feedback they received, even when it landed only as inline notes. FAILED feedback's text is still shown — it was composed
     * and may have reached them on the artifact — but it carries no response handle, because only DELIVERED
     * feedback can be answered.
     */
    public static ObservationDetailDTO from(
            Observation observation,
            @Nullable ObservationFeedback feedback,
            @Nullable String nextStep,
            @Nullable String artifactUrl,
            boolean includeEvidence,
            @Nullable ObservationInvalidation invalidation) {
        var practice = observation.getPractice();
        return new ObservationDetailDTO(
                observation.getId(),
                practice.getSlug(),
                practice.getName(),
                observation.getArtifactKind(),
                observation.getArtifactId(),
                observation.getSummary(),
                observation.getOutcome(),
                observation.getSeverity(),
                includeEvidence ? ObservationEvidenceDTO.from(observation.getEvidence()) : null,
                observation.getEvidenceRationale(),
                feedback == null ? null : feedback.getBody(),
                nextStep,
                responseTo(feedback),
                observation.getRecurrenceKey(),
                ReviewClaimCurrentness.of(observation.getPracticeRevision(), practice, observation.getSupersededAt()),
                invalidation == null ? null : invalidation.getInvalidatedAt(),
                invalidation == null ? null : invalidation.getReason(),
                observation.getOrigin(),
                artifactUrl,
                observation.getObservedAt());
    }

    /** No handle, no response: feedback that failed to deliver cannot be answered, so it carries none. */
    private static @Nullable FeedbackResponseDTO responseTo(@Nullable ObservationFeedback feedback) {
        if (feedback == null) {
            return null;
        }
        UUID feedbackId = feedback.getFeedbackId();
        return feedbackId == null ? null : FeedbackResponseDTO.from(feedbackId, feedback);
    }
}
