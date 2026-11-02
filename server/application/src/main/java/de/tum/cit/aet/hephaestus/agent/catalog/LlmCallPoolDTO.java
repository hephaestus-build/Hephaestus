package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Provider capacity that the models in it share")
public record LlmCallPoolDTO(
        @NonNull @Schema(description = "Pool id") Long id,

        @NonNull @Schema(description = "Unique slug; also the pool label of its metrics")
        String slug,

        @NonNull @Schema(description = "Human-readable name")
        String displayName,

        @Nullable @Schema(description = "Ceiling on the learned limit; null leaves it uncapped")
        Integer maxConcurrentCalls,

        @Schema(
                description = "Calls in flight the provider is thought to serve, learned from its answers",
                requiredMode = RequiredMode.REQUIRED)
        int learnedLimit,

        @Schema(
                description = "The calls in flight the pool allows now: the learned limit under the ceiling",
                requiredMode = RequiredMode.REQUIRED)
        int effectiveLimit,

        @Schema(
                description = "How many of the effective limit's calls only conversations may use",
                requiredMode = RequiredMode.REQUIRED)
        int conversationReserve,

        @Schema(
                description = "True for the pool a model has of its own; false for one that models share",
                requiredMode = RequiredMode.REQUIRED)
        boolean dedicated,

        @Nullable @Schema(description = "When a provider's refusal last lowered the learned limit")
        Instant throttledAt,

        @Nullable @Schema(description = "The provider asked for no call before this time")
        Instant noCallBefore,

        @Nullable Integer requestsPerMinute,
        @Nullable Long tokensPerMinute,
        @Nullable Long tokensPerCall) {

    public static LlmCallPoolDTO from(LlmCallPool pool) {
        int effective = LlmCallLimit.effective(pool.getLearnedLimit(), pool.getMaxConcurrentCalls());
        return new LlmCallPoolDTO(
                pool.getId(),
                pool.getSlug(),
                pool.getDisplayName(),
                pool.getMaxConcurrentCalls(),
                pool.getLearnedLimit(),
                effective,
                LlmCallLimit.reserve(effective),
                pool.isDedicated(),
                pool.getThrottledAt(),
                pool.getNoCallBefore(),
                pool.getRequestsPerMinute(),
                pool.getTokensPerMinute(),
                pool.getTokensPerCall());
    }
}
