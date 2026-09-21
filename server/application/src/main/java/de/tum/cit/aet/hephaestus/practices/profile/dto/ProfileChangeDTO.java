package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One thing that changed on the developer's practice profile since the previous review run.
 *
 * <p>A fact, not a sentence: the page composes its own copy from the type and the fields, so a change to the
 * wording never needs a server release. Practice-level changes carry the practice and its group; a
 * {@code GROUP_MOVED} carries only the group. {@code from} and {@code to} are the standing or trend values
 * the type says they are.
 */
@Schema(description = "One change on the developer's practice profile since the previous review run")
public record ProfileChangeDTO(
        @NonNull
        @Schema(
                description = "What changed",
                allowableValues = {
                    "FEEDBACK_NEW",
                    "FEEDBACK_RESOLVED",
                    "STANDING_MOVED",
                    "TREND_TURNED",
                    "GROUP_MOVED",
                    "FIRST_OBSERVED"
                })
        Type type,

        @NonNull
        @Schema(
                description = "When it changed: the observation, the run that composed the feedback, or the"
                        + " response that did it")
        Instant at,

        @Nullable String practiceSlug,
        @Nullable String practiceName,
        @Nullable String groupSlug,
        @Nullable String groupName,

        @Nullable @Schema(description = "The standing or trend before, for a move or a turn")
        String from,

        @Nullable @Schema(description = "The standing or trend now, for a move or a turn")
        String to,

        @Nullable @Schema(description = "The feedback this is about, for a feedback change")
        UUID feedbackId,

        @Nullable
        @Schema(
                description = "What resolved the feedback, for a FEEDBACK_RESOLVED change: the developer's work"
                        + " coming back clean, or the developer marking it addressed",
                allowableValues = {"WORK", "DEVELOPER"})
        ResolvedBy resolvedBy,

        @NonNull
        @Schema(
                description = "The reviewed work that drove the change, newest first: for feedback the work resolved,"
                        + " the pieces of work that came back clean")
        List<ReviewedWorkRefDTO> evidence) {
    public enum Type {
        /** Practice feedback about a habit was prepared for the developer. */
        FEEDBACK_NEW,
        /** A piece of practice feedback was resolved, by the work or by the developer: {@link ResolvedBy}. */
        FEEDBACK_RESOLVED,
        /** The practice's standing is not what it was before the previous run. */
        STANDING_MOVED,
        /** The practice's trend direction is not what it was before the previous run. */
        TREND_TURNED,
        /** The group's standing is not what it was before the previous run. */
        GROUP_MOVED,
        /** The practice recorded its first observation about the developer. */
        FIRST_OBSERVED,
    }

    public enum ResolvedBy {
        /** {@code WorkResolution.CLEAN_NEEDED} pieces of the developer's work in a row came back clean on the practice. */
        WORK,
        /** The developer marked the feedback addressed. */
        DEVELOPER,
    }
}
