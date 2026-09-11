package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A model on a workspace's own AI provider connection. Unlike the instance catalog, price is
 * inline on the model — no history — since a workspace's own spend has no separate temporal audit.
 */
@Schema(description = "A model on your AI provider")
public record WorkspaceLlmModelDTO(
        @NonNull @Schema(description = "Model id") Long id,

        @NonNull @Schema(description = "Owning connection id")
        Long connectionId,

        @NonNull @Schema(description = "Owning connection's display name")
        String connectionDisplayName,

        @NonNull @Schema(description = "Unique slug within the workspace")
        String slug,

        @NonNull @Schema(description = "Human-readable name")
        String displayName,

        @NonNull @Schema(description = "Upstream provider model id")
        String upstreamModelId,

        @Nullable @Schema(description = "Context window in tokens")
        Integer contextWindow,

        @Nullable @Schema(description = "Maximum output tokens")
        Integer maxOutputTokens,

        @NonNull @Schema(description = "Whether the model supports a reasoning mode")
        Boolean supportsReasoning,

        @Nullable @Schema(description = "Who operates the systems the work is sent to; null until declared")
        LlmDataOperator operatedBy,

        @Nullable @Schema(description = "What stays behind after the reply; null until declared")
        LlmDataRetention keptAfterReply,

        @Nullable @Schema(description = "Admin-only note: region, agreement, renewal date")
        String dataHandlingNote,

        @NonNull @Schema(description = "Data-handling tier derived from the two facts; UNDECLARED until both are set")
        DataHandlingTier dataHandlingTier,

        @NonNull @Schema(description = "Active toggle") Boolean enabled,
        @NonNull @Schema(description = "Pricing mode") PricingMode pricingMode,

        @Nullable @Schema(description = "Input rate per 1M tokens (USD)")
        BigDecimal per1mInputUsd,

        @Nullable @Schema(description = "Output rate per 1M tokens (USD)")
        BigDecimal per1mOutputUsd,

        @Nullable @Schema(description = "Cache-read rate per 1M tokens (USD)")
        BigDecimal per1mCacheReadUsd,

        @Nullable @Schema(description = "Cache-write rate per 1M tokens (USD)")
        BigDecimal per1mCacheWriteUsd,

        @NonNull @Schema(description = "Currency code") String currency,
        @Nullable @Schema(description = "Price note") String priceNote,
        @NonNull @Schema(description = "Creation timestamp") Instant createdAt,

        @Nullable @Schema(description = "Last update timestamp")
        Instant updatedAt) {
    public static WorkspaceLlmModelDTO from(WorkspaceLlmModel model) {
        return new WorkspaceLlmModelDTO(
                model.getId(),
                model.getConnection().getId(),
                model.getConnection().getDisplayName(),
                model.getSlug(),
                model.getDisplayName(),
                model.getUpstreamModelId(),
                model.getContextWindow(),
                model.getMaxOutputTokens(),
                model.isSupportsReasoning(),
                model.getDataHandling().getOperatedBy(),
                model.getDataHandling().getKeptAfterReply(),
                model.getDataHandling().getNote(),
                model.getDataHandlingTier(),
                model.isEnabled(),
                model.getPricingMode(),
                model.getPer1mInputUsd(),
                model.getPer1mOutputUsd(),
                model.getPer1mCacheReadUsd(),
                model.getPer1mCacheWriteUsd(),
                model.getCurrency(),
                model.getPriceNote(),
                model.getCreatedAt(),
                model.getUpdatedAt());
    }
}
