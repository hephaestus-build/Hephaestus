package de.tum.cit.aet.hephaestus.conversation;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * What Hephaestus used for one answer (ADR 0157 decision 8), from what its turn stored. The answer's links are what it
 * cited. Every turn also reads the conversation before it answers, so the app says that in a fixed line.
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

        @Schema(description = "What the turn tried to open and could not") @NonNull
        List<ConversationUsedSourceDTO> couldNotCheck) {}
