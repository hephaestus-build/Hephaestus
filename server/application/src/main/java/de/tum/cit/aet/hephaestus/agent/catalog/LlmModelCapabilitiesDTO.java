package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** A synthetic probe result, not a claim about the quality of the model's answers. */
public record LlmModelCapabilitiesDTO(
        @Nullable @Schema(description = "forced_tool_choice; null when the probe could not establish support")
        Boolean forcedToolChoice) {}
