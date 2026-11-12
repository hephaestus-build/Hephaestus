package de.tum.cit.aet.hephaestus.agent.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "The decision, embedding and reranking calls of this month, in total. calls, "
                + "inputTokens, outputTokens and both costs equal the sums of the byPractice entries. reviews and "
                + "unpricedEventCount do not: one review can count for several practices there.")
public record LlmUsagePracticeScriptTotalDTO(
        @NonNull
        @Schema(description = "Distinct reviews in which a practice script called a model for practice scripts.")
        Long reviews,

        @NonNull Long calls,
        @NonNull Long inputTokens,
        @NonNull Long outputTokens,

        @NonNull @Schema(description = "Confirmed practice script spend on shared (instance) models, in USD.")
        BigDecimal instanceTotalCostUsd,

        @NonNull @Schema(description = "Practice script spend on this workspace's own provider(s), in USD.")
        BigDecimal ownProviderTotalCostUsd,

        @NonNull
        @Schema(
                description = "Distinct reviews with a practice script call whose price is not yet known. Those "
                        + "rows are excluded from both totals above.")
        Long unpricedEventCount) {}
