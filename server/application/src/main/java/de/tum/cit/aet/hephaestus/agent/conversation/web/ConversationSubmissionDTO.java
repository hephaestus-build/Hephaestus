package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "A stored question and the turn that answers it")
public record ConversationSubmissionDTO(
        @NonNull ConversationTurnDTO turn, @NonNull ConversationEntryDTO question) {}
