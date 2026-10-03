package de.tum.cit.aet.hephaestus.agent.catalog;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Shared pricing validation for the instance catalog and workspace BYO models — same rule, two owners
 * of the rates. The magnitude bound lives here rather than only in the request DTOs' {@code @Digits}
 * so no entry point can widen it independently.
 */
final class LlmPriceValidation {

    /**
     * Rates are {@code NUMERIC(18,8)}. At scale 8 a binary64 round-trips exactly only below
     * {@code 10^7}, so this — not the column — is the real ceiling: a larger rate would be quoted back
     * to the admin as a different number than the one stored.
     */
    static final BigDecimal MAX_RATE_EXCLUSIVE = new BigDecimal("10000000");

    private LlmPriceValidation() {}

    static void validate(
            PricingMode pricingMode,
            @Nullable BigDecimal per1mInputUsd,
            @Nullable BigDecimal per1mOutputUsd,
            @Nullable BigDecimal per1mCacheReadUsd,
            @Nullable BigDecimal per1mCacheWriteUsd,
            @Nullable String note) {
        List<BigDecimal> rates = Arrays.asList(per1mInputUsd, per1mOutputUsd, per1mCacheReadUsd, per1mCacheWriteUsd);
        boolean anyRatePresent = rates.stream().anyMatch(rate -> rate != null);

        if (pricingMode == PricingMode.PRICED) {
            if (per1mInputUsd == null || per1mOutputUsd == null) {
                throw new IllegalArgumentException(
                        "A price requires at least an input rate and an output rate (per 1M tokens).");
            }
            boolean anyNegative = rates.stream().anyMatch(rate -> rate != null && rate.signum() < 0);
            if (anyNegative) {
                throw new IllegalArgumentException("Rates must be zero or greater.");
            }
            boolean anyPositive = rates.stream().anyMatch(rate -> rate != null && rate.signum() > 0);
            if (!anyPositive) {
                throw new IllegalArgumentException(
                        "A price requires at least one rate greater than zero. For a model without usage charges, choose No metered API cost instead.");
            }
            boolean anyTooLarge =
                    rates.stream().anyMatch(rate -> rate != null && rate.compareTo(MAX_RATE_EXCLUSIVE) >= 0);
            if (anyTooLarge) {
                throw new IllegalArgumentException(
                        "Rates must be below " + MAX_RATE_EXCLUSIVE.toPlainString() + " per 1M tokens.");
            }
        } else {
            if (anyRatePresent) {
                throw new IllegalArgumentException(
                        "Rates can only be set when the model has a price; clear them or set a price first.");
            }
            if (pricingMode == PricingMode.NO_CHARGE && !StringUtils.hasText(note)) {
                throw new IllegalArgumentException(
                        "An explanation of why this model has no metered API cost (e.g. self-hosted) is required.");
            }
        }
    }
}
