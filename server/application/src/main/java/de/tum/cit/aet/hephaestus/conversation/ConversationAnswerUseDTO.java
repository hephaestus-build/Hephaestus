package de.tum.cit.aet.hephaestus.conversation;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * What Hephaestus used for one answer (ADR 0157 decision 8), from what its turn stored. Every turn also reads the
 * conversation before it answers, and the page that the person asked from, if any, so the app says that in a fixed line.
 */
@Schema(description = "What Hephaestus opened for one answer, and what it could not open")
public record ConversationAnswerUseDTO(
        @Schema(
                description =
                        "False when the turn stored no record of what it did. The app then says that Hephaestus did"
                                + " not record what the answer used.",
                requiredMode = RequiredMode.REQUIRED)
        boolean recorded,

        @Schema(description = "What the turn opened, in the order of its first use") @NonNull
        List<ConversationUsedSourceDTO> read,

        @Schema(description = "What the turn tried to open and could not, or has no stored result for") @NonNull
        List<ConversationUsedSourceDTO> couldNotCheck,

        @Schema(
                description = "Whether a message of the answer links an observation, a problem or feedback",
                requiredMode = RequiredMode.REQUIRED)
        boolean cited,

        @Schema(
                description = "Whether the person asked from a page of the app, which the answer also read",
                requiredMode = RequiredMode.REQUIRED)
        boolean askedFromPage) {

    /** A turn that stored no record of what it did. */
    static ConversationAnswerUseDTO unrecorded(boolean cited, boolean askedFromPage) {
        return new ConversationAnswerUseDTO(false, List.of(), List.of(), cited, askedFromPage);
    }
}
