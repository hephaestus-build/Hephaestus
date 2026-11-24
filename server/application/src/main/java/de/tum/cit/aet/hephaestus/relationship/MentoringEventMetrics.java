package de.tum.cit.aet.hephaestus.relationship;

import de.tum.cit.aet.hephaestus.relationship.metrics.MentoringMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Records instance-wide mentoring event counts. */
public final class MentoringEventMetrics {

    private final Map<MentoringLogEvent, Counter> counters;

    public MentoringEventMetrics(MeterRegistry registry) {
        var byKind = new EnumMap<MentoringLogEvent, Counter>(MentoringLogEvent.class);
        for (MentoringLogEvent event : MentoringLogEvent.values()) {
            var kind = MentoringLogField.of(MentoringLogKey.KIND, event);
            byKind.put(
                    event, registry.counter(MentoringMetrics.EVENTS, MentoringLogKey.KIND.key(), kind.metricLabel()));
        }
        counters = Map.copyOf(byKind);
    }

    public void count(MentoringLogEvent event) {
        Objects.requireNonNull(counters.get(event)).increment();
    }
}
