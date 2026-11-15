package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

@Schema(description = "The developer's vote on one of Hephaestus's messages")
public record ConversationVoteRequestDTO(@NotNull ConversationEntry.@NonNull Vote vote) {}
