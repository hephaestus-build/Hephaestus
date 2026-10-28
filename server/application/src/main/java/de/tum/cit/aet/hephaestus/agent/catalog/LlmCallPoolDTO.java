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

        @Nullable @Schema(description = "Most model calls in flight at once; null does not limit them")
        Integer maxConcurrentCalls,

        @Schema(description = "Calls only Heph conversations may use", requiredMode = RequiredMode.REQUIRED)
        int interactiveReserve,

        @Nullable @Schema(description = "The provider asked for no call before this time")
        Instant noCallBefore,

        @Nullable Integer requestsPerMinute,
        @Nullable Long tokensPerMinute,
        @Nullable Long tokensPerCall) {

    public static LlmCallPoolDTO from(LlmCallPool pool) {
        return new LlmCallPoolDTO(
                pool.getId(),
                pool.getSlug(),
                pool.getDisplayName(),
                pool.getMaxConcurrentCalls(),
                pool.getInteractiveReserve(),
                pool.getNoCallBefore(),
                pool.getRequestsPerMinute(),
                pool.getTokensPerMinute(),
                pool.getTokensPerCall());
    }
}
