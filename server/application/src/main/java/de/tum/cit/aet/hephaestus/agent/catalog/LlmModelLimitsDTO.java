package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The token limits a model runs with, each with its source ({@link ModelLimits}). */
@Schema(description = "Token limits the model runs with, each with where its value comes from")
public record LlmModelLimitsDTO(
        @Nullable @Schema(description = "Context window in tokens; null when unknown")
        Integer contextWindow,

        @Nullable @Schema(description = "Where the context window comes from; null when unknown")
        LlmLimitSource contextWindowSource,

        @Nullable @Schema(description = "Maximum output tokens of one call; null when unknown")
        Integer maxOutputTokens,

        @Nullable @Schema(description = "Where the maximum output comes from; null when unknown")
        LlmLimitSource maxOutputTokensSource,

        @NonNull
        @Schema(
                description =
                        "Whether calls can run: false until an admin sets a maximum output or a context window, or the"
                                + " provider reports one")
        Boolean bounded) {
    static LlmModelLimitsDTO from(ModelLimits limits) {
        return new LlmModelLimitsDTO(
                limits.contextWindow(),
                limits.contextWindowSource(),
                limits.maxOutputTokens(),
                limits.maxOutputTokensSource(),
                limits.bounded());
    }
}
