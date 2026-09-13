package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Audit snapshot of a model on a workspace's "bring your own" LLM connection. The credential lives on
 * {@link WorkspaceLlmConnection}, not here, so every field is safe to record verbatim.
 */
record WorkspaceLlmModelSnapshot(
        String slug,
        String displayName,
        String upstreamModelId,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        boolean supportsReasoning,
        @Nullable LlmDataOperator operatedBy,
        @Nullable LlmDataRetention keptAfterReply,
        @Nullable String dataHandlingNote,
        PricingMode pricingMode,
        @Nullable BigDecimal per1mInputUsd,
        @Nullable BigDecimal per1mOutputUsd,
        @Nullable BigDecimal per1mCacheReadUsd,
        @Nullable BigDecimal per1mCacheWriteUsd,
        String currency,
        @Nullable String priceNote,
        boolean enabled)
        implements ConfigAuditSnapshot {
    static WorkspaceLlmModelSnapshot of(WorkspaceLlmModel m) {
        return new WorkspaceLlmModelSnapshot(
                m.getSlug(),
                m.getDisplayName(),
                m.getUpstreamModelId(),
                m.getContextWindow(),
                m.getMaxOutputTokens(),
                m.isSupportsReasoning(),
                m.getDataHandling().getOperatedBy(),
                m.getDataHandling().getKeptAfterReply(),
                m.getDataHandling().getNote(),
                m.getPricingMode(),
                m.getPer1mInputUsd(),
                m.getPer1mOutputUsd(),
                m.getPer1mCacheReadUsd(),
                m.getPer1mCacheWriteUsd(),
                m.getCurrency(),
                m.getPriceNote(),
                m.isEnabled());
    }
}
