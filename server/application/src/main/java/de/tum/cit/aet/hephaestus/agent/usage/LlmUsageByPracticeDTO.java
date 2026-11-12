package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "The decision, embedding and reranking calls that one practice's script made this month. "
                + "Each call names the practice that made it.")
public record LlmUsageByPracticeDTO(
        @Nullable
        @Schema(
                description = "The practice slug. Null for calls that name no practice: calls recorded before each "
                        + "call had its own row, and calls of a deleted practice.")
        String practiceSlug,

        @Nullable @Schema(description = "The practice name. Null when the slug names no current practice.")
        String practiceName,

        @NonNull @Schema(description = "The models for practice scripts that the script called.")
        List<AgentPurpose> purposes,

        @NonNull
        @Schema(
                description = "Reviews in which the script called a model for practice scripts. A review with several "
                        + "practices counts for each of them.")
        Long reviews,

        @NonNull Long calls,
        @NonNull Long inputTokens,
        @NonNull Long outputTokens,

        @NonNull @Schema(description = "This practice's spend on shared (instance) models, in USD.")
        BigDecimal instanceTotalCostUsd,

        @NonNull @Schema(description = "This practice's spend on this workspace's own provider(s), in USD.")
        BigDecimal ownProviderTotalCostUsd,

        @NonNull
        @Schema(
                description = "Reviews with a call of this practice whose price is not yet known, each review "
                        + "counted once. Those calls are excluded from both totals above. A review with several "
                        + "practices counts for each of them.")
        Long unpricedEventCount) {}
