package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** How Hephaestus's own thread changes end, by direction and outcome. */
@Component
@ConditionalOnServerRole
public class FeedbackThreadMeters {
    private final MeterRegistry registry;

    /** Registers every series at zero, so {@code increase()} sees the first change of each outcome after a start. */
    public FeedbackThreadMeters(MeterRegistry registry) {
        this.registry = registry;
        for (boolean resolved : new boolean[] {true, false}) {
            counter(resolved, null);
            for (FeedbackThreadStopReason reason : FeedbackThreadStopReason.values()) counter(resolved, reason);
        }
        for (IntegrationKind provider : new IntegrationKind[] {IntegrationKind.GITHUB, IntegrationKind.GITLAB})
            registry.counter(
                    PracticeMetrics.FEEDBACK_THREAD_ACTOR_MISMATCH,
                    "provider",
                    provider.name().toLowerCase(Locale.ROOT));
    }

    /** A thread event that resembled Hephaestus's own write but named another account or none. */
    void actorMismatch(@Nullable IntegrationKind provider) {
        registry.counter(
                        PracticeMetrics.FEEDBACK_THREAD_ACTOR_MISMATCH,
                        "provider",
                        provider == null ? "unknown" : provider.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    /** One change that ended: confirmed when {@code stopped} is null. */
    void record(boolean resolved, @Nullable FeedbackThreadStopReason stopped) {
        counter(resolved, stopped).increment();
    }

    private Counter counter(boolean resolved, @Nullable FeedbackThreadStopReason stopped) {
        return registry.counter(
                PracticeMetrics.FEEDBACK_THREAD_CHANGES,
                "direction",
                resolved ? "resolve" : "reopen",
                "outcome",
                stopped == null ? "done" : stopped.name().toLowerCase(Locale.ROOT));
    }
}
