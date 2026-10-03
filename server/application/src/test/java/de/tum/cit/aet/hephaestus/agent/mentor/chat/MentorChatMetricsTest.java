package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract reviewers care about: every {@link MentorChatMetrics.Outcome} ends up on a
 * pre-registered Counter (no per-call builder allocation), the duration timer brackets correctly,
 * and the cost summary swallows negative / non-finite inputs instead of polluting dashboards.
 */
class MentorChatMetricsTest extends BaseUnitTest {

    private MeterRegistry registry;
    private MentorChatMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MentorChatMetrics(registry);
    }

    // No standalone `recordStartedIncrementsCounter` test: `everyOutcomeRoutesToPreRegisteredCounter`
    // already covers wiring, and the started/completed lockstep invariant is asserted IN-FLIGHT
    // through MentorChatServiceTest.assertOutcomeRecorded — that's where the invariant matters.

    @Test
    void everyOutcomeRoutesToPreRegisteredCounter() {
        for (MentorChatMetrics.Outcome o : MentorChatMetrics.Outcome.values()) {
            metrics.recordCompleted(o);
        }
        for (MentorChatMetrics.Outcome o : MentorChatMetrics.Outcome.values()) {
            assertThat(registry.get("mentor.turn.completed")
                            .tag("outcome", o.tag())
                            .counter()
                            .count())
                    .as("counter for %s should be 1", o)
                    .isEqualTo(1d);
        }
        // Total = N outcomes × 1 increment.
        long total = registry.find("mentor.turn.completed").meters().stream()
                .mapToLong(m -> Math.round(((Counter) m).count()))
                .sum();
        assertThat(total).isEqualTo(MentorChatMetrics.Outcome.values().length);
    }

    @Test
    @DisplayName("startTimer / stopTimer record one observation on mentor.turn.duration")
    void timerSampleStops() {
        Timer.Sample sample = metrics.startTimer();
        metrics.stopTimer(sample);
        assertThat(registry.get("mentor.turn.duration").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("recordCostUsd accepts finite non-negative; drops NaN / -∞ / negative")
    void costFiltersInvalidInputs() {
        metrics.recordCostUsd(0.12);
        metrics.recordCostUsd(-1.0); // rejected
        metrics.recordCostUsd(Double.NaN); // rejected
        metrics.recordCostUsd(Double.NEGATIVE_INFINITY); // rejected
        metrics.recordCostUsd(Double.POSITIVE_INFINITY); // rejected — non-finite
        assertThat(registry.get("mentor.turn.cost.usd").summary().count()).isEqualTo(1L);
        assertThat(registry.get("mentor.turn.cost.usd").summary().totalAmount()).isEqualTo(0.12);
    }
}
