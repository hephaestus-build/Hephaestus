package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentJobBackoffTest extends BaseUnitTest {

    /** The midpoint: zero jitter, so the base value comes through exactly. */
    private static final RandomGenerator NO_JITTER = fixed(0.5);

    /** The maximum +10% jitter. */
    private static final RandomGenerator MAX_POSITIVE_JITTER = fixed(1.0);

    /** The maximum -10% jitter. */
    private static final RandomGenerator MAX_NEGATIVE_JITTER = fixed(0.0);

    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public double nextDouble() {
                return value;
            }

            @Override
            public long nextLong() {
                return 0L;
            }
        };
    }

    @Test
    @DisplayName("attempt 1 with no jitter is base^4 + 15 = 16s")
    void attemptOneNoJitter() {
        assertThat(AgentJobBackoff.compute(1, NO_JITTER)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("attempt 0 (and negative, defensively) is treated as attempt 0: 0^4 + 15 = 15s")
    void attemptZeroOrNegativeFloorsToZero() {
        assertThat(AgentJobBackoff.compute(0, NO_JITTER)).isEqualTo(Duration.ofSeconds(15));
        assertThat(AgentJobBackoff.compute(-5, NO_JITTER)).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("attempt 3 with no jitter is 3^4 + 15 = 96s")
    void attemptThreeNoJitter() {
        assertThat(AgentJobBackoff.compute(3, NO_JITTER)).isEqualTo(Duration.ofSeconds(96));
    }

    @Test
    @DisplayName("caps at 15 minutes regardless of how high the attempt number climbs")
    void capsAtFifteenMinutes() {
        // The literal, not AgentJobBackoff.CAP: the number in this test's name is the operator-visible
        // promise, and reading the cap back from production would let any value satisfy it.
        assertThat(AgentJobBackoff.compute(100, NO_JITTER)).isEqualTo(Duration.ofMinutes(15));
        assertThat(AgentJobBackoff.compute(1000, NO_JITTER)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("the cap is applied after jitter, so even maximum positive jitter cannot exceed 15 minutes")
    void capIsAppliedAfterJitterSoMaximumPositiveJitterNeverExceedsIt() {
        assertThat(AgentJobBackoff.compute(100, MAX_POSITIVE_JITTER))
                .as("no wait may exceed the cap, whichever way the jitter goes")
                .isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("jitter is bounded to +/-10% of the uncapped base")
    void jitterIsBoundedToTenPercent() {
        Duration base = AgentJobBackoff.compute(3, NO_JITTER);
        Duration maxUp = AgentJobBackoff.compute(3, MAX_POSITIVE_JITTER);
        Duration maxDown = AgentJobBackoff.compute(3, MAX_NEGATIVE_JITTER);

        assertThat(maxUp.getSeconds()).isCloseTo((long) (base.getSeconds() * 1.10), Offset.offset(1L));
        assertThat(maxDown.getSeconds()).isCloseTo((long) (base.getSeconds() * 0.90), Offset.offset(1L));
    }

    /**
     * {@code nextDouble()} is bounded to {@code [0, 1)}, so the jitter multiplier never falls below 0.9 —
     * the {@code Math.max(1, …)} floor in {@code compute} sits below every reachable value and no legal
     * input exercises it; this pins the reachable minimum instead.
     */
    @Test
    @DisplayName("never returns zero or negative, even at the smallest attempt with max negative jitter")
    void neverReturnsZeroOrNegative() {
        assertThat(AgentJobBackoff.compute(0, MAX_NEGATIVE_JITTER).isNegative()).isFalse();
        assertThat(AgentJobBackoff.compute(0, MAX_NEGATIVE_JITTER).isZero()).isFalse();
    }

    @Test
    @DisplayName("the real (unseeded) overload never throws and stays within [1s, cap * 1.1]")
    void realRandomOverloadStaysInBounds() {
        for (int attempt = 0; attempt <= 10; attempt++) {
            Duration d = AgentJobBackoff.compute(attempt);
            assertThat(d).isPositive();
            assertThat(d)
                    .isLessThanOrEqualTo(AgentJobBackoff.CAP.plusSeconds(AgentJobBackoff.CAP.toSeconds() / 10 + 1));
        }
    }
}
