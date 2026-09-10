package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Partial update of a model's metadata. Every field is optional; an absent (null) field keeps
 * its current value. Pricing and sharing are updated through their own endpoints, not here.
 */
@Schema(description = "Update a model's metadata (all fields optional; pricing and sharing are separate)")
public record UpdateLlmModelRequestDTO(
        @Nullable @Size(max = 128) @Schema(description = "Human-readable name")
        String displayName,

        @Nullable @Min(1) @Schema(description = "Context window in tokens")
        Integer contextWindow,

        @Nullable @Min(1) @Schema(description = "Maximum output tokens")
        Integer maxOutputTokens,

        @Nullable @Schema(description = "Whether the model supports a reasoning mode")
        Boolean supportsReasoning,

        @Nullable
        @Schema(
                description =
                        "Operator-declared processing location; unclassified models cannot serve a member's explicit location choice")
        LlmProcessingLocation processingLocation,

        @Nullable @Schema(description = "Active toggle (off = existing settings stop working)")
        Boolean enabled) {}
