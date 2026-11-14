package de.tum.cit.aet.hephaestus.agent.catalog;

/**
 * Whether a registered model can run now, and the first reason it cannot. Both admin consoles show this one answer, and
 * activation refuses a model for the same reasons.
 */
public enum LlmModelReadiness {
    READY,
    /** It has no price and is not marked as having no cost, so it cannot be turned on. */
    PRICE_MISSING,
    CONNECTION_OFF,
    OFF,
    /** Hephaestus refuses its calls until it knows the model's output limit. */
    LIMITS_MISSING,
    /** An instance model shared with selected workspaces that names none: no workspace's practice reviews can use it. */
    NO_WORKSPACE_ACCESS;

    static LlmModelReadiness of(
            LlmCatalogModel model, boolean connectionEnabled, PricingMode pricing, boolean workspaceAccess) {
        if (pricing == PricingMode.UNPRICED) return PRICE_MISSING;
        if (!connectionEnabled) return CONNECTION_OFF;
        if (!model.isEnabled()) return OFF;
        if (!model.limits().bounded()) return LIMITS_MISSING;
        if (!workspaceAccess) return NO_WORKSPACE_ACCESS;
        return READY;
    }

    /** A model turns on only on an enabled connection and with a price, which may be no cost. */
    static void requireActivatable(boolean connectionEnabled, PricingMode pricing) {
        if (!connectionEnabled || pricing == PricingMode.UNPRICED)
            throw new IllegalArgumentException(
                    "Activate the connection and set a price before you activate the model.");
    }
}
