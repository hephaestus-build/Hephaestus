package de.tum.cit.aet.hephaestus.practices.observation.trend.dto;

import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendSupport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record TrendSupportDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int currentOpportunities,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int previousOpportunities,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Distinct pieces of reviewed work the trend rests on, across both bundles")
        int opportunities,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int opportunitiesUntilComparable,
        @Nullable Integer comparablePractices,
        @Nullable Integer eligiblePractices,
        @Nullable Instant firstOpportunityAt,
        @Nullable Instant lastOpportunityAt,
        @Nullable Integer calendarSpanDays,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int bundleSize,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double ropeHalfWidth,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double credibilityThreshold) {
    public static TrendSupportDTO from(TrendSupport support) {
        return new TrendSupportDTO(
                support.currentOpportunities(),
                support.previousOpportunities(),
                support.opportunities(),
                support.opportunitiesUntilComparable(),
                support.comparablePractices(),
                support.eligiblePractices(),
                support.firstOpportunityAt(),
                support.lastOpportunityAt(),
                support.calendarSpanDays(),
                support.bundleSize(),
                support.ropeHalfWidth(),
                support.credibilityThreshold());
    }
}
