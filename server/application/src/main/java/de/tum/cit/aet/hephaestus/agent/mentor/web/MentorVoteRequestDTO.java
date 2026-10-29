package de.tum.cit.aet.hephaestus.agent.mentor.web;

import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

@Schema(description = "The developer's vote on one of Hephaestus's messages")
public record MentorVoteRequestDTO(@NotNull MentorEntry.@NonNull Vote vote) {}
