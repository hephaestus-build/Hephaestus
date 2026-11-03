package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class LlmCallDelayTest {

    private static final int LIMIT = 8;

    /** Feeds a whole window, at a limit of eight, whose fastest first token is {@code fastest}. */
    private static LlmCallDelay.Step window(LlmCallDelay from, int fastest, int limit) {
        var delay = from;
        for (int i = 0; i < Math.max(4, limit / 2) - 1; i++)
            delay = delay.afterFirstToken(fastest + 100_000, limit).delay();
        return delay.afterFirstToken(fastest, limit);
    }

    @Test
    void shouldTakeTheFirstWindowsFastestFirstTokenAsTheBaselineAndKeepTheLimit() {
        var step = window(new LlmCallDelay(null, null, 0), 900, LIMIT);

        assertThat(step.keep()).isEqualTo(1);
        assertThat(step.delay()).isEqualTo(new LlmCallDelay(900, null, 0));
    }

    @Test
    void shouldDecideNothingBeforeTheWindowIsFull() {
        var step = new LlmCallDelay(900, null, 0).afterFirstToken(60_000, LIMIT);

        assertThat(step.keep()).isEqualTo(1);
        assertThat(step.delay()).isEqualTo(new LlmCallDelay(900, 60_000, 1));
    }

    @Test
    void shouldLetOneShortPromptClearAWindowOfLongOnes() {
        var step = window(new LlmCallDelay(900, null, 0), 1_500, LIMIT);

        assertThat(step.keep()).isEqualTo(1);
    }

    @Test
    void shouldLowerTheLimitInProportionWhenEvenTheFastestCallWaited() {
        // 900 ms allows 2.8 s.
        assertThat(window(new LlmCallDelay(900, null, 0), 3_500, LIMIT).keep()).isEqualTo(0.8);
    }

    @Test
    void shouldFollowAFasterProviderDownward() {
        assertThat(window(new LlmCallDelay(900, null, 0), 200, LIMIT).delay().baselineMillis())
                .isEqualTo(200);
    }

    @Test
    void shouldTakeANewBaselineAtTheFloorInsteadOfLoweringFurther() {
        var step = window(new LlmCallDelay(900, null, 0), 20_000, LlmCallLimit.FLOOR);

        assertThat(step.keep()).isEqualTo(1);
        assertThat(step.delay().baselineMillis()).isEqualTo(20_000);
    }

    @Test
    void shouldNotCountACallThatWaitedAsEvidenceForAHigherLimit() {
        var delay = new LlmCallDelay(900, null, 0);

        assertThat(delay.queued(2_800)).isFalse();
        assertThat(delay.queued(2_801)).isTrue();
        assertThat(new LlmCallDelay(null, null, 0).queued(100_000)).isFalse();
    }

    @Test
    void shouldLowerByOneCallAtLeastAndNeverByMoreThanARefusal() {
        assertThat(new LlmCallLimit(32, 5).afterDelay(0.99)).isEqualTo(new LlmCallLimit(31, 0));
        assertThat(new LlmCallLimit(32, 5).afterDelay(0.8)).isEqualTo(new LlmCallLimit(25, 0));
        assertThat(new LlmCallLimit(32, 5).afterDelay(0.01)).isEqualTo(new LlmCallLimit(22, 0));
        assertThat(new LlmCallLimit(2, 0).afterDelay(0.01)).isEqualTo(new LlmCallLimit(2, 0));
    }
}
