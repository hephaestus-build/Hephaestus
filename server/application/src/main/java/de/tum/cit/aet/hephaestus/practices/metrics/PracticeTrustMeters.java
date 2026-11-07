package de.tum.cit.aet.hephaestus.practices.metrics;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Whether people trust the results: what reviews conclude, what people mark, and how developers answer. A practice is
 * no label, because each workspace can add practices. Call these after the commit.
 */
@Component
public class PracticeTrustMeters {

    private final MeterRegistry registry;

    /** Registers every series at zero, so that {@code increase()} also sees the first event after a start. */
    public PracticeTrustMeters(MeterRegistry registry) {
        this.registry = registry;
        for (ResultOutcome outcome : ResultOutcome.values()) recordedCounter(outcome);
        for (ResultMarkKind kind : ResultMarkKind.values()) {
            markedCounter(kind, false);
            markedCounter(kind, true);
        }
        for (FeedbackResolution resolution : FeedbackResolution.values()) respondedCounter(resolution);
    }

    public void recorded(ResultOutcome outcome) {
        recordedCounter(outcome).increment();
    }

    public void marked(ResultMarkKind kind, boolean removed) {
        markedCounter(kind, removed).increment();
    }

    public void responded(FeedbackResolution resolution) {
        respondedCounter(resolution).increment();
    }

    private Counter recordedCounter(ResultOutcome outcome) {
        return registry.counter(PracticeMetrics.RESULT_RECORDED, "outcome", label(outcome));
    }

    private Counter markedCounter(ResultMarkKind kind, boolean removed) {
        return registry.counter(
                PracticeMetrics.RESULT_MARKED, "kind", label(kind), "change", removed ? "removed" : "set");
    }

    private Counter respondedCounter(FeedbackResolution resolution) {
        return registry.counter(PracticeMetrics.FEEDBACK_RESPONSE, "resolution", label(resolution));
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
