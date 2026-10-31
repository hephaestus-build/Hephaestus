package de.tum.cit.aet.hephaestus.productfeedback;

import static de.tum.cit.aet.hephaestus.productfeedback.metrics.ProductFeedbackMetrics.OLDEST_WAITING;
import static de.tum.cit.aet.hephaestus.productfeedback.metrics.ProductFeedbackMetrics.PROMOTIONS;
import static de.tum.cit.aet.hephaestus.productfeedback.metrics.ProductFeedbackMetrics.ROUTES;
import static de.tum.cit.aet.hephaestus.productfeedback.metrics.ProductFeedbackMetrics.STEPS;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The product feedback signals. Every label comes from a closed set. A rising share of private routes other than
 * {@code opted_out} means that the gate is too strict or redaction is too weak.
 */
@Component
class ProductFeedbackMeters {
    private final MeterRegistry registry;
    private final AtomicLong oldestWaitingSeconds = new AtomicLong();

    ProductFeedbackMeters(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge(OLDEST_WAITING, oldestWaitingSeconds);
    }

    /** The age of the oldest report not yet on GitHub or in the admin email; 0 when none waits. */
    void oldestWaiting(long seconds) {
        oldestWaitingSeconds.set(seconds);
    }

    void route(FilingRoute route, FilingReason reason) {
        registry.counter(ROUTES, "route", route.name().toLowerCase(Locale.ROOT), "reason", reason.tag())
                .increment();
    }

    void draft(String outcome) {
        registry.counter(STEPS, "step", "draft", "outcome", outcome).increment();
    }

    void step(String step, String outcome) {
        registry.counter(STEPS, "step", step, "outcome", outcome).increment();
    }

    void promotion(String outcome) {
        registry.counter(PROMOTIONS, "outcome", outcome).increment();
    }
}
