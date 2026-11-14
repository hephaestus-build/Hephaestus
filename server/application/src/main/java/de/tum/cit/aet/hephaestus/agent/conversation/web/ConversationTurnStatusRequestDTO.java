package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

@Schema(description = "A status change the developer asks for on a turn")
public record ConversationTurnStatusRequestDTO(
        @NotNull
        @Schema(
                description = "STOPPED stops a queued or running turn. Hephaestus keeps what it already wrote.",
                allowableValues = {"STOPPED"})
        ConversationTurn.@NonNull Status status) {}
