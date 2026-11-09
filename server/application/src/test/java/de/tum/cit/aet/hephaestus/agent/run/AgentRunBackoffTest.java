package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentRunBackoffTest extends BaseUnitTest {

    private static final RandomGenerator NO_JITTER = fixed(0.5);

    private static final RandomGenerator MAX_POSITIVE_JITTER = fixed(1.0);

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
        assertThat(AgentRuns.backoff(1, NO_JITTER)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("attempt 0 (and negative, defensively) is treated as attempt 0: 0^4 + 15 = 15s")
    void attemptZeroOrNegativeFloorsToZero() {
        assertThat(AgentRuns.backoff(0, NO_JITTER)).isEqualTo(Duration.ofSeconds(15));
        assertThat(AgentRuns.backoff(-5, NO_JITTER)).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("attempt 3 with no jitter is 3^4 + 15 = 96s")
    void attemptThreeNoJitter() {
        assertThat(AgentRuns.backoff(3, NO_JITTER)).isEqualTo(Duration.ofSeconds(96));
    }

    @Test
    @DisplayName("caps at 15 minutes regardless of how high the attempt number climbs")
    void capsAtFifteenMinutes() {
        // The literal, not AgentRuns.BACKOFF_CAP: the number in this test's name is the operator-visible
        // promise, and reading the cap back from production would let any value satisfy it.
        assertThat(AgentRuns.backoff(100, NO_JITTER)).isEqualTo(Duration.ofMinutes(15));
        assertThat(AgentRuns.backoff(1000, NO_JITTER)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("the cap is applied after jitter, so even maximum positive jitter cannot exceed 15 minutes")
    void capIsAppliedAfterJitterSoMaximumPositiveJitterNeverExceedsIt() {
        assertThat(AgentRuns.backoff(100, MAX_POSITIVE_JITTER))
                .as("no wait may exceed the cap, whichever way the jitter goes")
                .isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("jitter is bounded to +/-10% of the uncapped base")
    void jitterIsBoundedToTenPercent() {
        Duration base = AgentRuns.backoff(3, NO_JITTER);
        Duration maxUp = AgentRuns.backoff(3, MAX_POSITIVE_JITTER);
        Duration maxDown = AgentRuns.backoff(3, MAX_NEGATIVE_JITTER);

        assertThat(maxUp.toSeconds()).isCloseTo((long) (base.toSeconds() * 1.10), Offset.offset(1L));
        assertThat(maxDown.toSeconds()).isCloseTo((long) (base.toSeconds() * 0.90), Offset.offset(1L));
    }

    // nextDouble is in [0,1), so legal jitter cannot exercise the unreachable minimum-delay floor.
    @Test
    @DisplayName("never returns zero or negative, even at the smallest attempt with max negative jitter")
    void neverReturnsZeroOrNegative() {
        assertThat(AgentRuns.backoff(0, MAX_NEGATIVE_JITTER).isNegative()).isFalse();
        assertThat(AgentRuns.backoff(0, MAX_NEGATIVE_JITTER).isZero()).isFalse();
    }

    @Test
    @DisplayName("the real (unseeded) overload never throws and stays within [1s, cap * 1.1]")
    void realRandomOverloadStaysInBounds() {
        for (int attempt = 0; attempt <= 10; attempt++) {
            Duration d = AgentRuns.backoff(attempt);
            assertThat(d).isPositive();
            assertThat(d)
                    .isLessThanOrEqualTo(AgentRuns.BACKOFF_CAP.plusSeconds(AgentRuns.BACKOFF_CAP.toSeconds() / 10 + 1));
        }
    }
}
