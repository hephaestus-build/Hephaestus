package de.tum.cit.aet.hephaestus.practices.groupdetail.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One concrete, evidence-backed observation from a review run")
public record PracticeGroupReviewObservationDTO(
        @NonNull UUID observationId,
        @Nullable UUID feedbackId,
        @Nullable FeedbackUsefulness feedbackUsefulness,
        @Nullable FeedbackResolution feedbackResolution,
        @Nullable String feedbackResponseComment,
        @NonNull String practiceSlug,
        @NonNull String practiceName,
        @NonNull String title,
        @NonNull AssessmentStatus assessmentStatus,
        @Nullable Presence presence,

        @Nullable @Schema(description = "Good or bad for the developer; null unless assessmentStatus is ASSESSED")
        Assessment assessment,

        @Nullable Severity severity,
        @Nullable String recurrenceKey) {
    @com.fasterxml.jackson.annotation.JsonProperty("outcome")
    @Schema(
            description = "Derived from presence and target assessment; null unless assessed",
            accessMode = Schema.AccessMode.READ_ONLY)
    public @Nullable Outcome getOutcome() {
        return Outcome.of(presence, assessment);
    }

    public static PracticeGroupReviewObservationDTO from(
            Observation observation,
            @Nullable UUID feedbackId,
            @Nullable FeedbackUsefulness feedbackUsefulness,
            @Nullable FeedbackResolution feedbackResolution,
            @Nullable String feedbackResponseComment) {
        return new PracticeGroupReviewObservationDTO(
                observation.getId(),
                feedbackId,
                feedbackUsefulness,
                feedbackResolution,
                feedbackResponseComment,
                observation.getPractice().getSlug(),
                observation.getPractice().getName(),
                observation.getSummary(),
                observation.getAssessmentStatus(),
                observation.getPresence(),
                observation.getAssessment(),
                observation.getSeverity(),
                observation.getRecurrenceKey());
    }
}
