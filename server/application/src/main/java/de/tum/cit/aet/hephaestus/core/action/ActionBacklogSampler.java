package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The outbox backlog as gauges: actions that have not ended, by kind and state, and how long the oldest due action of
 * each kind has waited. A due action that waits long means that no replica's executor takes it, or that its lane is
 * held by an action that does not end. One query each pass, off the request paths. A failed pass keeps the last values,
 * so a database blip does not read as an empty outbox.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("The outbox backlog of the whole instance; its gauges name no workspace")
public class ActionBacklogSampler {

    private static final Logger log = LoggerFactory.getLogger(ActionBacklogSampler.class);
    /** The states of an action that has not ended. */
    private static final ActionState[] OPEN = {ActionState.PROPOSED, ActionState.ADMITTED, ActionState.EXECUTING};

    private final AgentActionRepository actions;
    private final Map<ActionKind, Map<ActionState, AtomicLong>> backlog = new EnumMap<>(ActionKind.class);
    private final Map<ActionKind, AtomicLong> oldestDue = new EnumMap<>(ActionKind.class);
    private final Counter failures;

    ActionBacklogSampler(AgentActionRepository actions, MeterRegistry meters) {
        this.actions = actions;
        // Every series exists from the start, so an alert on a backlog that is still empty has a series to read.
        for (ActionKind kind : ActionKind.values()) {
            Map<ActionState, AtomicLong> states = new EnumMap<>(ActionState.class);
            for (ActionState state : OPEN) {
                AtomicLong count = new AtomicLong();
                states.put(state, count);
                Gauge.builder(CoreMetrics.ACTION_BACKLOG, count, AtomicLong::get)
                        .description("Outbox actions that have not ended, by kind and state")
                        .tag("kind", kind.name())
                        .tag("state", state.name())
                        .register(meters);
            }
            backlog.put(kind, states);
            AtomicLong age = new AtomicLong();
            oldestDue.put(kind, age);
            Gauge.builder(CoreMetrics.ACTION_OLDEST_DUE_SECONDS, age, AtomicLong::get)
                    .description(
                            "Seconds since the oldest due outbox action of the kind became due; 0 when none is due")
                    .tag("kind", kind.name())
                    .register(meters);
        }
        failures = Counter.builder(CoreMetrics.ACTION_BACKLOG_SAMPLER_FAILURES)
                .description("Outbox backlog passes that failed; the gauges kept their last values")
                .register(meters);
    }

    @Scheduled(fixedDelay = 30, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void sample() {
        try {
            Map<ActionKind, Map<ActionState, Long>> counts = new EnumMap<>(ActionKind.class);
            Map<ActionKind, Long> ages = new EnumMap<>(ActionKind.class);
            for (var row : actions.backlog(ActionKind.names())) {
                ActionKind kind = ActionKind.valueOf(row.getKind());
                counts.computeIfAbsent(kind, ignored -> new EnumMap<>(ActionState.class))
                        .put(ActionState.valueOf(row.getState()), row.getActions());
                ages.merge(kind, row.getOldestDueSeconds(), Math::max);
            }
            // Published only after the whole pass read, so no scrape sees a half-written outbox.
            backlog.forEach((kind, states) -> states.forEach((state, count) ->
                    count.set(counts.getOrDefault(kind, Map.of()).getOrDefault(state, 0L))));
            oldestDue.forEach((kind, age) -> age.set(ages.getOrDefault(kind, 0L)));
        } catch (RuntimeException e) {
            failures.increment();
            log.atWarn()
                    .addKeyValue("event.name", "action.backlog.sampler_failed")
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Outbox backlog pass failed; the gauges keep their last values");
        }
    }
}
