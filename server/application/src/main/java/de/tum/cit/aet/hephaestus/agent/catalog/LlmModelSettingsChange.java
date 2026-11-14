package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.spi.AiModelBrand;
import org.jspecify.annotations.Nullable;

/**
 * A partial change to an instance or workspace model's settings, from its update request: an absent value keeps the
 * current one, and a clear flag removes it.
 */
public record LlmModelSettingsChange(
        @Nullable String displayName,
        @Nullable Integer contextWindow,
        @Nullable Boolean clearContextWindow,
        @Nullable Integer maxOutputTokens,
        @Nullable Boolean clearMaxOutputTokens,
        @Nullable ReasoningEffort reasoningEffort,
        @Nullable Boolean clearReasoningEffort,
        @Nullable AiModelBrand brand,
        @Nullable Boolean clearBrand,
        @Nullable LlmDataOperator operatedBy,
        @Nullable String dataHandlingNote,
        @Nullable Boolean clearDataHandling,
        @Nullable Boolean enabled) {}
