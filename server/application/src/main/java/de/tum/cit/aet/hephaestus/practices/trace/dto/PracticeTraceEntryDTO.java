package de.tum.cit.aet.hephaestus.practices.trace.dto;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeSignalDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.trace.PracticeTraceOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One practice's answer for one artifact, on two axes that must not be collapsed.
 *
 * <p>{@code outcome} is about the <em>measurement</em>: did this practice get assessed, and if not,
 * why. The counts and {@code withheldReasons} are about the <em>intervention</em>: did anything reach
 * a person. A practice at {@code HUMAN_APPROVAL} is {@code REVIEWED} with nothing delivered, and reporting
 * that as one number would hide the exact distinction autonomy states exist to make.
 */
@Schema(description = "What became of one practice on this artifact, and whether anyone heard about it")
public record PracticeTraceEntryDTO(
        @NonNull String practiceSlug,
        @NonNull String practiceName,

        @Schema(
                description = "Slug of the practice group this practice sits in; null for a practice the workspace "
                        + "files in no group")
        @Nullable
        String groupSlug,

        @Schema(description = "That group's name, as the workspace spells it; null when the practice has no group")
        @Nullable
        String groupName,

        @NonNull
        @Schema(description = "How much autonomy the workspace currently gives this practice, after inheritance")
        PracticeAutonomy autonomy,

        @NonNull PracticeTraceOutcome outcome,

        @NonNull @Schema(description = "The outcome in a sentence, phrased as what would change it")
        String explanation,

        @NonNull @Schema(description = "The signals this practice watches, each with its display name")
        List<PracticeSignalDTO> watches,

        @Schema(
                description = "The signal of the occurrence this answer is about, with its display name; null when "
                        + "nothing it watches happened")
        @Nullable
        PracticeSignalDTO occasionedBy,

        @Schema(
                description = "That occurrence's id in this trace's signals list. The name alone cannot identify "
                        + "it — the same signal recurs on every revision — so this is what a link should follow.")
        @Nullable
        UUID occasionedById,

        @Schema(description = "When the answer was settled") @Nullable
        Instant decidedAt,

        @Schema(description = "The review this answer came from, when one ran") @Nullable
        UUID reviewId,

        @NonNull @Schema(description = "Measurements this practice produced on this artifact")
        Integer observationCount,

        @NonNull @Schema(description = "Interventions actually delivered to a person")
        Integer deliveredCount,

        @NonNull
        @Schema(
                description = "Why prepared feedback was withheld. Non-empty with observations present means we "
                        + "measured and deliberately said nothing.")
        List<FeedbackSuppressionReason> withheldReasons) {}
