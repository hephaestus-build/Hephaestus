package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("unit")
class LlmCallLimitTest {

    @ParameterizedTest
    @CsvSource({"32,22", "8,5", "5,3", "3,2", "2,2"})
    void shouldLowerTheLimitByAFractionAndKeepRoomForTwoCalls(int learned, int lowered) {
        assertThat(new LlmCallLimit(learned, 3).afterThrottle()).isEqualTo(new LlmCallLimit(lowered, 0));
    }

    @Test
    void shouldRiseWithEveryServedCallWhileProbing() {
        assertThat(new LlmCallLimit(8, 0).afterServedCall(true)).isEqualTo(new LlmCallLimit(9, 0));
    }

    @Test
    void shouldRiseByOneForAWholeLimitOfServedCallsOnceTheProviderRefused() {
        LlmCallLimit limit = new LlmCallLimit(4, 0);
        for (int served = 0; served < 3; served++) {
            limit = limit.afterServedCall(false);
            assertThat(limit.learned()).isEqualTo(4);
        }
        assertThat(limit.afterServedCall(false)).isEqualTo(new LlmCallLimit(5, 0));
    }

    @ParameterizedTest
    @CsvSource({"1,0", "2,1", "4,1", "5,2", "8,2", "32,8", "33,9", "100,25"})
    void shouldScaleTheConversationReserveWithTheLimitAndLeaveABackgroundCall(int limit, int reserve) {
        assertThat(LlmCallLimit.reserve(limit)).isEqualTo(reserve);
        assertThat(limit - LlmCallLimit.reserve(limit)).isPositive();
    }

    @Test
    void shouldHonourAnAdminsMaximumAsACeiling() {
        assertThat(LlmCallLimit.effective(40, 32)).isEqualTo(32);
        assertThat(LlmCallLimit.effective(8, 32)).isEqualTo(8);
        assertThat(LlmCallLimit.effective(40, null)).isEqualTo(40);
    }
}
