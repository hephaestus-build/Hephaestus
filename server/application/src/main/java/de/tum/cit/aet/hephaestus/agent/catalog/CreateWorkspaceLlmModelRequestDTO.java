package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Omitted {@code pricingMode} defaults to {@code UNPRICED}. */
@Schema(description = "Create a model on your AI provider")
public record CreateWorkspaceLlmModelRequestDTO(
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

        @Nullable @Schema(description = "Who operates the systems the work is sent to; declare both facts or neither")
        LlmDataOperator operatedBy,

        @Nullable @Schema(description = "What stays behind after the reply; declare both facts or neither")
        LlmDataRetention keptAfterReply,

        @Nullable @Size(max = 200) @Schema(description = "Admin-only note: region, agreement, renewal date")
        String dataHandlingNote,

        @Nullable @Schema(description = "Whether the model is active (default false)")
        Boolean enabled,

        @Nullable @Schema(description = "Pricing mode (default UNPRICED)")
        PricingMode pricingMode,

        @Nullable @Schema(description = "Input rate per 1M tokens (USD); required when the model has a price")
        BigDecimal per1mInputUsd,

        @Nullable @Schema(description = "Output rate per 1M tokens (USD); required when the model has a price")
        BigDecimal per1mOutputUsd,

        @Nullable @Schema(description = "Cache-read rate per 1M tokens (USD), if applicable")
        BigDecimal per1mCacheReadUsd,

        @Nullable @Schema(description = "Cache-write rate per 1M tokens (USD), if applicable")
        BigDecimal per1mCacheWriteUsd,

        @Nullable
        @Size(max = 500)
        @Schema(description = "Note; required when the model is free (e.g. self-hosted, no cost)")
        String priceNote) {}
