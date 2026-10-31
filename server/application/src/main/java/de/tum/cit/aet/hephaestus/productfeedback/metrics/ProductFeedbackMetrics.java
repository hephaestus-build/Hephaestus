package de.tum.cit.aet.hephaestus.productfeedback.metrics;

/** The meter names of product feedback on GitHub. Every label value comes from a closed set. */
public final class ProductFeedbackMetrics {
    private ProductFeedbackMetrics() {}

    /** Routing outcomes, by route and reason. A rising private share means the gate is too strict or redaction too weak. */
    public static final String ROUTES = "product_feedback_routes";

    /** Drafting, filing, follow-up, transfer and cleanup steps, by outcome. */
    public static final String STEPS = "product_feedback_steps";

    /** {@code /publish} commands, by outcome. */
    public static final String PROMOTIONS = "product_feedback_promotions";

    /** The age of the oldest report not yet on GitHub or in the admin email. */
    public static final String OLDEST_WAITING = "product_feedback_oldest_waiting_seconds";
}
