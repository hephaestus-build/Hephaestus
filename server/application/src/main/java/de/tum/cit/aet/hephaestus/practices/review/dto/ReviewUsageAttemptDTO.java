package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewUsageAttemptDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID jobId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int attempt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long inputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long reasoningTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long cacheReadTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long cacheWriteTokens,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String costUsd,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UsagePricingState pricingState,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether costUsd counts the upper bound of a call whose usage was not measured, so the"
                        + " attempt cost at most costUsd")
        boolean includesUpperBound) {}
