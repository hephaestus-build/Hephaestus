package de.tum.cit.aet.hephaestus.agent.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;

/**
 * The calls of one purse that count at their upper bound: calls still at their provider, and calls whose usage was not
 * measured. Never an exact cost: the calls cost at most {@code upperBoundUsd}.
 */
@Schema(description = "Calls of one purse whose usage was not measured, and the most they can cost")
public record LlmUnmeasuredUsageDTO(
        @NonNull @Schema(description = "Calls this month that are still running or whose provider reported no usage.")
        Long calls,

        @NonNull
        @Schema(
                description = "The most these calls can cost, in USD: each call's request size and output limit at its"
                        + " frozen rates. The cap counts it on top of the confirmed spend. Not an exact cost.")
        BigDecimal upperBoundUsd) {}
