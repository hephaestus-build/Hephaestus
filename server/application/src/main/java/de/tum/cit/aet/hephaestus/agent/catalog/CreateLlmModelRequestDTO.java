package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Pricing and sharing are set separately. */
@Schema(description = "Create a model on an instance LLM connection")
public record CreateLlmModelRequestDTO(
        @Nullable
        @Size(max = 63)
        @Schema(description = "Optional internal slug; generated from displayName when omitted")
        String slug,

        @NonNull @NotBlank @Size(max = 128) @Schema(description = "Human-readable name")
        String displayName,

        @NonNull @NotBlank @Size(max = 256) @Schema(description = "Upstream provider model id")
        String upstreamModelId,

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

        @Nullable @Schema(description = "Whether the model is active (default false)")
        Boolean enabled) {}
