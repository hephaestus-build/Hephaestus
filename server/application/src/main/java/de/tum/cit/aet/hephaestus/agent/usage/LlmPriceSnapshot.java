package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

public record LlmPriceSnapshot(
        UsageFundingSource fundingSource,
        UsagePricingState pricingState,
        @Nullable Long appliedPriceId,
        @Nullable Long appliedWorkspaceModelId,
        @Nullable BigDecimal per1mInputUsd,
        @Nullable BigDecimal per1mOutputUsd,
        @Nullable BigDecimal per1mCacheReadUsd,
        @Nullable BigDecimal per1mCacheWriteUsd) {
    private static final BigDecimal PER_1M = BigDecimal.valueOf(1_000_000L);

    /**
     * Decimal places the ledger keeps: {@code llm_usage_event.cost_usd} is {@code NUMERIC(18,6)}.
     */
    static final int LEDGER_SCALE = 6;

    /** Banker's rounding, so a long run of amounts does not drift up. */
    static final RoundingMode LEDGER_ROUNDING = RoundingMode.HALF_EVEN;

    private static final MathContext CALC = new MathContext(20, LEDGER_ROUNDING);

    private static final BigDecimal MIN_COST = BigDecimal.ONE.movePointLeft(LEDGER_SCALE);

    // Bound costs to the precision binary64 can preserve on the JSON wire.
    private static final BigDecimal EXACT_ON_WIRE_CEILING = BigDecimal.valueOf(1_000_000_000L);

    private static final BigDecimal MAX_COST = EXACT_ON_WIRE_CEILING.subtract(MIN_COST);

    /**
     * The price of an attempt whose real price cannot be recovered. Instance-funded because the
     * host's shared models are what an unattributed run consumed; UNPRICED so the month reads
     * unverifiable rather than free.
     */
    public static LlmPriceSnapshot unpricedInstance() {
        return new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE, UsagePricingState.UNPRICED, null, null, null, null, null, null);
    }

    /**
     * Which way a stored cost was moved to fit the ledger column, when it was not stored as
     * computed.
     */
    public enum CostClamp {
        /** Round a positive sub-micro-dollar cost upward so paid usage remains distinct from free usage. */
        ROUNDED_UP_TO_MINIMUM,
        /** Clamping high costs underbills and lowers every budget computed from them. */
        CAPPED_AT_MAXIMUM,
    }

    /**
     * @param usd the amount to store; {@code null} only when no price was resolved at all
     * @param clamp {@code null} when {@code usd} is exactly what the frozen rates produced
     */
    public record Cost(@Nullable BigDecimal usd, @Nullable CostClamp clamp) {
        static Cost exact(@Nullable BigDecimal usd) {
            return new Cost(usd, null);
        }
    }

    /**
     * Takes no reasoning tokens: they are already inside {@code outputTokens} and must not be
     * priced twice.
     */
    public Cost calculateCost(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        if (pricingState == UsagePricingState.UNPRICED) return Cost.exact(null);
        if (pricingState == UsagePricingState.NO_CHARGE) return Cost.exact(BigDecimal.ZERO.setScale(LEDGER_SCALE));
        BigDecimal raw = bucket(inputTokens, per1mInputUsd, CALC)
                .add(bucket(outputTokens, per1mOutputUsd, CALC), CALC)
                .add(bucket(cacheReadTokens, per1mCacheReadUsd, CALC), CALC)
                .add(bucket(cacheWriteTokens, per1mCacheWriteUsd, CALC), CALC);
        if (raw.signum() < 0) throw new IllegalStateException("Frozen LLM price produced a negative cost");
        BigDecimal rounded = raw.setScale(LEDGER_SCALE, LEDGER_ROUNDING);
        if (raw.signum() > 0 && rounded.signum() == 0) {
            return new Cost(MIN_COST, CostClamp.ROUNDED_UP_TO_MINIMUM);
        }
        if (rounded.compareTo(EXACT_ON_WIRE_CEILING) >= 0) {
            return new Cost(MAX_COST, CostClamp.CAPPED_AT_MAXIMUM);
        }
        return Cost.exact(rounded);
    }

    /**
     * The most that one call can cost: its whole input at the highest input rate (plain, cache read or cache write),
     * and its output limit at the output rate. A token covers at least one byte, so the request's bytes bound its input
     * tokens. Rounded up to the ledger's scale. Tokens that a provider adds on its own side, such as a hidden system
     * prompt, and output that a server writes past a limit that it ignores are not in it.
     *
     * @param inputBytes the bytes of the request body
     * @param outputTokens the call's output limit; 0 for a call that writes no output
     * @return {@code null} when no bound is known: an unpriced model, or a priced model without the rate the bound
     *     needs
     */
    public @Nullable BigDecimal upperBound(long inputBytes, long outputTokens) {
        if (pricingState == UsagePricingState.UNPRICED) return null;
        if (pricingState == UsagePricingState.NO_CHARGE) return BigDecimal.ZERO.setScale(LEDGER_SCALE);
        if (per1mInputUsd == null || (outputTokens > 0 && per1mOutputUsd == null)) return null;
        BigDecimal inputRate = per1mInputUsd;
        if (per1mCacheReadUsd != null) inputRate = inputRate.max(per1mCacheReadUsd);
        if (per1mCacheWriteUsd != null) inputRate = inputRate.max(per1mCacheWriteUsd);
        BigDecimal raw = bucket(inputBytes, inputRate, CALC).add(bucket(outputTokens, per1mOutputUsd, CALC), CALC);
        BigDecimal bound = raw.setScale(LEDGER_SCALE, RoundingMode.CEILING);
        return bound.compareTo(MAX_COST) > 0 ? MAX_COST : bound;
    }

    private static BigDecimal bucket(long tokens, @Nullable BigDecimal rate, MathContext mc) {
        if (tokens <= 0 || rate == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(tokens).divide(PER_1M, mc).multiply(rate, mc);
    }
}
