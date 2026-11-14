package de.tum.cit.aet.hephaestus.agent.catalog;

import org.jspecify.annotations.Nullable;

/**
 * Declared pricing intent for a model. {@code NO_CHARGE} asserts zero cost and counts as $0 spend;
 * {@code UNPRICED} asserts nothing and makes the month's budget verdict UNVERIFIABLE rather than
 * silently under-counting.
 */
public enum PricingMode {
    PRICED,
    NO_CHARGE,
    UNPRICED;

    /** An instance model's pricing: its current price's mode, or unpriced while it has none. */
    static PricingMode of(@Nullable LlmModelPrice price) {
        return price == null ? UNPRICED : price.getPricingMode();
    }
}
