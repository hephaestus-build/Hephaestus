package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One attempt's model calls.
 *
 * @param costUsd what the calls cost; it counts each open or unmeasured call at its upper bound. {@code null} when one
 *     call has neither a price nor a bound
 * @param includesUpperBound whether {@code costUsd} counts an upper bound, so it is at most this much and not exact
 */
public record ReviewUsageAttempt(
        UUID jobId,
        int attempt,
        long calls,
        long inputTokens,
        long outputTokens,
        long reasoningTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        @Nullable BigDecimal costUsd,
        UsagePricingState pricingState,
        boolean includesUpperBound) {}
