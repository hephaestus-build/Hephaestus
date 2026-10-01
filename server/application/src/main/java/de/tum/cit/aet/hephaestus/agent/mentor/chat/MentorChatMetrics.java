package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Micrometer instruments for the mentor chat turn pipeline.
 *
 * <p>The naming convention follows the rest of {@code agent/} ({@code agent.job.*},
 * {@code llm.proxy.*}): dot-case Java identifiers that Micrometer's Prometheus renderer
 * transforms to {@code mentor_turn_started_total} etc. Outcome tag values are kept to a fixed
 * enum so cardinality stays bounded.
 *
 * <p>Wired up in {@link MentorChatService}. {@code mentor.turn.started} fires on inbound
 * start before executor submission (so rejected submissions still increment);
 * {@code mentor.turn.completed} fires at every terminal branch with a single
 * {@link Outcome} label. {@code mentor.turn.first_token} runs from the turn being accepted to its
 * first text chunk, tagged by whether the turn found its sandbox warm or had to wait for one to start.
 */
@Component
public class MentorChatMetrics {

    /**
     * Terminal outcome tagged on {@code mentor.turn.completed}. {@link #IN_FLIGHT_CONFLICT_LOCAL}
     * = same-JVM lock rejection; {@link #IN_FLIGHT_CONFLICT_DB} = durable partial-unique index
     * trip (cross-replica race). The split is intentional so the DB-side firing is observable.
     */
    public enum Outcome {
        SUCCESS("success"),
        CLIENT_DISCONNECT("client_disconnect"),
        POISONED("poisoned"),
        TIMEOUT("timeout"),
        IN_FLIGHT_CONFLICT_LOCAL("in_flight_conflict_local"),
        IN_FLIGHT_CONFLICT_DB("in_flight_conflict_db"),
        REJECTED("rejected"),
        ERROR("error");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;
    private final Counter started;
    private final Map<Outcome, Counter> completedByOutcome;
    private final Timer duration;
    private final Timer runtimeReady;
    private final Timer firstTokenWarm;
    private final Timer firstTokenCold;
    private final DistributionSummary costUsd;

    public MentorChatMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.started = Counter.builder(AgentMetrics.MENTOR_TURN_STARTED)
                .description(
                        "Mentor chat turns observed at executor submit — increments on every dispatch, regardless of lock outcome.")
                .register(registry);
        this.duration = Timer.builder(AgentMetrics.MENTOR_TURN_DURATION)
                .description("Mentor chat turn wall-clock duration including sandbox attach + Pi RPC.")
                .publishPercentileHistogram()
                .register(registry);
        this.runtimeReady = Timer.builder(AgentMetrics.MENTOR_TURN_RUNTIME_READY)
                .description("Time from turn acceptance until open_thread confirms SDK runtime usability.")
                .publishPercentileHistogram()
                .register(registry);
        this.firstTokenWarm = firstTokenTimer(registry, "warm");
        this.firstTokenCold = firstTokenTimer(registry, "cold");
        this.costUsd = DistributionSummary.builder(AgentMetrics.MENTOR_TURN_COST_USD)
                .description("Per-turn LLM cost in USD (skipped for turns where cost is unresolvable).")
                .baseUnit("USD")
                .register(registry);

        // Pre-register one Counter per outcome at construction. Avoids per-call
        // Counter.builder + Tags allocation on the hot path (Micrometer perf wiki).
        this.completedByOutcome = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) {
            completedByOutcome.put(
                    o,
                    Counter.builder(AgentMetrics.MENTOR_TURN_COMPLETED)
                            .description("Mentor chat turns terminated, tagged by outcome.")
                            .tag("outcome", o.tag())
                            .register(registry));
        }
    }

    public void recordStarted() {
        started.increment();
    }

    public void recordCompleted(Outcome outcome) {
        Objects.requireNonNull(completedByOutcome.get(outcome)).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(duration);
    }

    public void recordRuntimeReady(Duration sinceAccepted) {
        runtimeReady.record(sinceAccepted);
    }

    public void recordFirstToken(boolean coldSandbox, Duration sinceAccepted) {
        (coldSandbox ? firstTokenCold : firstTokenWarm).record(sinceAccepted);
    }

    private static Timer firstTokenTimer(MeterRegistry registry, String sandbox) {
        return Timer.builder(AgentMetrics.MENTOR_TURN_FIRST_TOKEN)
                .description("Time from a mentor turn being accepted to its first text chunk.")
                .tag("sandbox", sandbox)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordCostUsd(double usd) {
        if (Double.isFinite(usd) && usd >= 0d) {
            costUsd.record(usd);
        }
    }

    /** A mentor turn was rejected by the monthly workspace LLM budget cap. */
    public void recordBudgetBlocked() {
        registry.counter(AgentMetrics.LLM_BUDGET_BLOCKED, "surface", "mentor").increment();
    }
}
