package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What one of Hephaestus's link tools showed in a message: a practice result, one of its problems, or prepared feedback,
 * with Hephaestus's words about it. A link to a result or problem is discussion; only shown prepared feedback is delivery.
 */
@Schema(description = "A practice result, problem or prepared feedback Hephaestus showed in a message")
public record MentorLinkDTO(
        @NonNull Kind kind,

        @Schema(description = "The result, problem or feedback id, by kind") @NonNull
        UUID id,

        @Schema(description = "Hephaestus's words about it, shown to the developer") @NonNull
        String text,

        @Schema(
                description = "The practice it is about: a problem's through its result, feedback's when it is about"
                        + " one practice; absent when the developer can no longer read it")
        @Nullable
        String practiceSlug,

        @Schema(
                description = "The item (pull request, issue, conversation or document) it is about, when the"
                        + " developer may still read it: a problem's through its result")
        @Nullable
        Long itemId) {

    /** Which link tool showed it: {@code link_result}, {@code link_problem} or {@code show_feedback}. */
    public enum Kind {
        RESULT,
        PROBLEM,
        FEEDBACK,
    }
}
