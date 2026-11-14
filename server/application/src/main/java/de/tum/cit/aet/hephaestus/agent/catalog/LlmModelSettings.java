package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.spi.AiModelBrand;
import org.jspecify.annotations.Nullable;

/** The settings that a new instance or workspace model takes from its create request. */
public interface LlmModelSettings {
    String displayName();

    String upstreamModelId();

    @Nullable
    Integer contextWindow();

    @Nullable
    Integer maxOutputTokens();

    @Nullable
    ReasoningEffort reasoningEffort();

    @Nullable
    AiModelBrand brand();

    @Nullable
    LlmDataOperator operatedBy();

    @Nullable
    String dataHandlingNote();
}
