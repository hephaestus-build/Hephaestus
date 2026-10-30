package de.tum.cit.aet.hephaestus.agent.mentor.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/** How far the developer read their conversation: the time of the newest answer they saw. */
@Schema(description = "How far the developer read their Hephaestus conversation")
public record MentorReadRequestDTO(
        @NonNull @NotNull @Schema(description = "The time of the newest answer the developer saw")
        Instant at) {}
