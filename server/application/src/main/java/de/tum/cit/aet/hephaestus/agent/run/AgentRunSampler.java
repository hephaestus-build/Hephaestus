package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The runs that have not ended, as gauges: how many are in each state and hold, how many are due, and how long the oldest
 * due run of each kind and band has waited. A due run that waits long means that no claim takes it. A held run is not
 * due, so a budget that stops a workspace reads as a hold and not as an idle fleet. One query each pass, off the request
 * paths. A failed pass keeps the last values, so a database blip does not read as an empty queue.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("The runs of the whole instance; its gauges name no workspace")
public class AgentRunSampler {

    private static final Logger log = LoggerFactory.getLogger(AgentRunSampler.class);
    /** The holds a queued run can have; a queued run can also have none, and a leased run has none. */
    private static final List<AgentRunHold> QUEUED_HOLDS = List.of(AgentRunHold.BUDGET, AgentRunHold.SANDBOX_ROOM);

    private record Slot(
            AgentRunKind kind,
            AgentRunBand band,
            AgentRunState state,
            @Nullable AgentRunHold hold) {}

    private record Subject(AgentRunKind kind, AgentRunBand band) {}

    private final AgentRunRepository runs;
    private final Map<Slot, AtomicLong> open = new HashMap<>();
    private final Map<Subject, AtomicLong> due = new HashMap<>();
    private final Map<Subject, AtomicLong> oldestDue = new HashMap<>();
    private final Counter failures;

    AgentRunSampler(AgentRunRepository runs, MeterRegistry meters) {
        this.runs = runs;
        // Every series exists from the start, so an alert on a backlog that is still empty has a series to read.
        for (AgentRunKind kind : AgentRunKind.values()) {
            for (AgentRunBand band : AgentRunBand.values()) {
                open(meters, new Slot(kind, band, AgentRunState.QUEUED, null));
                for (AgentRunHold hold : QUEUED_HOLDS) open(meters, new Slot(kind, band, AgentRunState.QUEUED, hold));
                open(meters, new Slot(kind, band, AgentRunState.LEASED, null));
                Subject subject = new Subject(kind, band);
                due.put(
                        subject,
                        gauge(
                                meters,
                                AgentMetrics.AGENT_RUN_DUE,
                                "Queued runs with no hold whose available_at has passed",
                                subject));
                oldestDue.put(
                        subject,
                        gauge(
                                meters,
                                AgentMetrics.AGENT_RUN_OLDEST_DUE_AGE_SECONDS,
                                "Seconds since the oldest due run became due; 0 when none is due",
                                subject));
            }
        }
        failures = Counter.builder(AgentMetrics.AGENT_RUN_SAMPLER_FAILURE)
                .description("Run sampler passes that failed; the gauges kept their last values")
                .register(meters);
    }

    private void open(MeterRegistry meters, Slot slot) {
        AtomicLong count = new AtomicLong();
        open.put(slot, count);
        Gauge.builder(AgentMetrics.AGENT_RUN_RUNS, count, AtomicLong::get)
                .description("Runs that have not ended, by kind, band, state and hold")
                .tag("kind", AgentRuns.tag(slot.kind()))
                .tag("band", AgentRuns.tag(slot.band()))
                .tag("state", AgentRuns.tag(slot.state()))
                .tag("hold_reason", slot.hold() == null ? "none" : AgentRuns.tag(slot.hold()))
                // Micrometer holds a gauge's value weakly; the registry must own it or the series reads NaN after a GC.
                .strongReference(true)
                .register(meters);
    }

    private static AtomicLong gauge(MeterRegistry meters, String name, String description, Subject subject) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::get)
                .description(description)
                .tag("kind", AgentRuns.tag(subject.kind()))
                .tag("band", AgentRuns.tag(subject.band()))
                .strongReference(true)
                .register(meters);
        return value;
    }

    @Scheduled(fixedDelay = 30, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void sample() {
        try {
            Map<Slot, Long> counts = new HashMap<>();
            Map<Subject, Long> dueCounts = new HashMap<>();
            Map<Subject, Long> ages = new HashMap<>();
            for (AgentRunRepository.OpenRow row : runs.open()) {
                AgentRunKind kind = AgentRunKind.valueOf(row.getKind());
                AgentRunBand band = AgentRunBand.valueOf(row.getBand());
                String hold = row.getHoldReason();
                counts.put(
                        new Slot(
                                kind,
                                band,
                                AgentRunState.valueOf(row.getState()),
                                hold == null ? null : AgentRunHold.valueOf(hold)),
                        row.getRuns());
                Subject subject = new Subject(kind, band);
                dueCounts.merge(subject, row.getDue(), Long::sum);
                ages.merge(subject, row.getOldestDueSeconds(), Math::max);
            }
            // Published only after the whole pass read, so no scrape sees a half-written backlog.
            open.forEach((slot, count) -> count.set(counts.getOrDefault(slot, 0L)));
            due.forEach((subject, count) -> count.set(dueCounts.getOrDefault(subject, 0L)));
            oldestDue.forEach((subject, age) -> age.set(ages.getOrDefault(subject, 0L)));
        } catch (RuntimeException e) {
            failures.increment();
            log.atWarn()
                    .addKeyValue("event.name", "agent.run.sampler_failed")
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Run sampler pass failed; the gauges keep their last values");
        }
    }
}
