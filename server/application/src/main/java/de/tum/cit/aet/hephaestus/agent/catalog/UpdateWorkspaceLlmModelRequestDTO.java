package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Partial update of a model on a workspace's own AI provider connection. Every field is optional; an
 * absent field keeps its current value.
 *
 * <p>Pricing is the exception: it is replaced wholesale, and only when {@code pricingMode} is given. A
 * rate sent without a {@code pricingMode} is ignored; a rate omitted alongside one is cleared.
 */
@Schema(description = "Update a model on your AI provider (all fields optional)")
public record UpdateWorkspaceLlmModelRequestDTO(
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

        @Nullable @Schema(description = "Active toggle") Boolean enabled,

        @Nullable @Schema(description = "Pricing mode; when given, replaces the price wholesale (see class docs)")
        PricingMode pricingMode,

        @Nullable @Schema(description = "Input rate per 1M tokens (USD)")
        BigDecimal per1mInputUsd,

        @Nullable @Schema(description = "Output rate per 1M tokens (USD)")
        BigDecimal per1mOutputUsd,

        @Nullable @Schema(description = "Cache-read rate per 1M tokens (USD), if applicable")
        BigDecimal per1mCacheReadUsd,

        @Nullable @Schema(description = "Cache-write rate per 1M tokens (USD), if applicable")
        BigDecimal per1mCacheWriteUsd,

        @Nullable
        @Size(max = 500)
        @Schema(description = "Note; required when the model is free (e.g. self-hosted, no cost)")
        String priceNote) {}
