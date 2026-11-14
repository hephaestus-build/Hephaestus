package de.tum.cit.aet.hephaestus.agent.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;

/** One job type's slice of a month, inside {@link WorkspaceLlmUsageReportDTO}. */
@Schema(description = "Month spend aggregated by job type")
public record LlmUsageByJobTypeDTO(
        @NonNull String jobType,

        @NonNull @Schema(description = "Confirmed spend on shared (instance) models for this job type, in USD.")
        BigDecimal instanceTotalCostUsd,

        @NonNull @Schema(description = "Spend on this workspace's own connected provider(s) for this job type, in USD.")
        BigDecimal ownProviderTotalCostUsd,

        @NonNull
        @Schema(
                description =
                        "Runs of this job type with at least one call whose cost is unavailable because a rate is missing or the measurement is unverified. A "
                                + "practice script call counts toward the run of its review. A capability test with unavailable cost counts once. Those calls are excluded from both "
                                + "totals above.")
        Long unpricedEventCount,

        @NonNull Long inputTokens,
        @NonNull Long outputTokens,
        @NonNull Long cacheReadTokens,
        @NonNull Long cacheWriteTokens,

        @NonNull
        @Schema(
                description = "LLM API calls that the proxy served. Practice reviews and conversation turns both "
                        + "include every assistant call in an internal tool loop.")
        Long totalCalls,

        @NonNull
        @Schema(
                description =
                        "Runs: job attempts and conversation turn attempts. A practice script call counts toward the run of its review.")
        Long events) {}
