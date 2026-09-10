package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Deliberately narrow: a workspace admin gets display names and price framing, never an upstream model
 * id, base URL, or auth material.
 */
@Schema(description = "A model available for this workspace to bind a Task to")
public record AvailableLlmModelDTO(
        @NonNull @Schema(description = "SHARED (instance catalog) or WORKSPACE (your own provider)")
        LlmModelScope scope,

        @NonNull @Schema(description = "Model id, unique within its scope")
        Long id,

        @NonNull @Schema(description = "Human-readable name")
        String displayName,

        @NonNull @Schema(description = "Owning connection's display name")
        String connectionDisplayName,

        @NonNull @Schema(description = "Whether the model supports a reasoning mode")
        Boolean supportsReasoning,

        @NonNull @Schema(description = "Operator-declared processing location")
        LlmProcessingLocation processingLocation,

        @NonNull @Schema(description = "Pricing mode") PricingMode pricingMode,

        @Nullable @Schema(description = "Input rate per 1M tokens (USD)")
        BigDecimal per1mInputUsd,

        @Nullable @Schema(description = "Output rate per 1M tokens (USD)")
        BigDecimal per1mOutputUsd,

        @Nullable @Schema(description = "Cache-read rate per 1M tokens (USD)")
        BigDecimal per1mCacheReadUsd,

        @Nullable @Schema(description = "Cache-write rate per 1M tokens (USD)")
        BigDecimal per1mCacheWriteUsd) {
    public static AvailableLlmModelDTO fromInstance(LlmModel model, @Nullable LlmModelPrice currentPrice) {
        PricingMode pricingMode = currentPrice != null ? currentPrice.getPricingMode() : PricingMode.UNPRICED;
        return new AvailableLlmModelDTO(
                LlmModelScope.SHARED,
                model.getId(),
                model.getDisplayName(),
                model.getConnection().getDisplayName(),
                model.isSupportsReasoning(),
                model.getProcessingLocation(),
                pricingMode,
                currentPrice != null ? currentPrice.getPer1mInputUsd() : null,
                currentPrice != null ? currentPrice.getPer1mOutputUsd() : null,
                currentPrice != null ? currentPrice.getPer1mCacheReadUsd() : null,
                currentPrice != null ? currentPrice.getPer1mCacheWriteUsd() : null);
    }

    public static AvailableLlmModelDTO fromWorkspace(WorkspaceLlmModel model) {
        return new AvailableLlmModelDTO(
                LlmModelScope.WORKSPACE,
                model.getId(),
                model.getDisplayName(),
                model.getConnection().getDisplayName(),
                model.isSupportsReasoning(),
                model.getProcessingLocation(),
                model.getPricingMode(),
                model.getPer1mInputUsd(),
                model.getPer1mOutputUsd(),
                model.getPer1mCacheReadUsd(),
                model.getPer1mCacheWriteUsd());
    }
}
