package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.FeedbackStateCounts;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.OperatorObservationRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
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

        @NonNull ReviewedWorkRefDTO reviewedWork,

        @Schema(description = "Whose work the observation is about; null when the identity is no longer resolvable")
        @Nullable
        ReviewSubjectDTO subject,

        @NonNull String summary,

        @NonNull @Schema(description = "Result of the practice review")
        Outcome outcome,

        @Schema(description = "Severity band (null unless outcome is NOT_MET)") @Nullable
        Severity severity,

        @Schema(description = "Cross-run locus key; null when continuity is unavailable") @Nullable
        String recurrenceKey,

        @NonNull
        @Schema(
                description = "What occasioned the measurement. BACKFILL came from a confirmed campaign over work "
                        + "that already existed, so it is not a point on the live trend line.")
        ObservationOrigin origin,

        @NonNull ReviewClaimCurrentness claimCurrentness,

        @Schema(description = "When a workspace admin invalidated this observation; null while it stands") @Nullable
        Instant invalidatedAt,

        @Schema(
                description = "When the developer last disputed feedback written from this observation; null while"
                        + " nothing about it is disputed")
        @Nullable
        Instant disputedAt,

        @NonNull Instant observedAt,

        @NonNull @Schema(description = "Counts of linked feedback by delivery state")
        ReviewFeedbackCountsDTO feedback) {

    public static ReviewObservationDTO from(
            OperatorObservationRow row,
            @Nullable FeedbackStateCounts feedback,
            ReviewedWorkRefDTO reviewedWork,
            Map<Long, ReviewSubjectDTO> subjects) {
        return new ReviewObservationDTO(
                row.getId(),
                row.getAgentJobId(),
                row.getPracticeSlug(),
                row.getPracticeName(),
                ReviewPracticeGroupDTO.from(
                        row.getGroupSlug(), row.getGroupName(), row.getGroupIcon(), row.getGroupColor()),
                reviewedWork,
                subjects.get(row.getAboutUserId()),
                row.getSummary(),
                row.getOutcome(),
                row.getSeverity(),
                row.getRecurrenceKey(),
                row.getOrigin(),
                ReviewClaimCurrentness.of(
                        row.getPracticeRevisionFingerprint(),
                        row.getCurrentPracticeRevisionFingerprint(),
                        row.getSupersededAt()),
                row.getInvalidatedAt(),
                row.getDisputedAt(),
                row.getObservedAt(),
                ReviewFeedbackCountsDTO.from(feedback));
    }
}
