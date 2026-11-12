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

    /**
     * Batch calls fill what the conversation reserve leaves: {@code limit - reserve(limit)}. Against a provider that
     * serves 12, that share first exceeds 12 at a limit of 18 (13 calls), and a refusal keeps 70 %, a limit of 12 (9
     * calls). From then on the limit climbs from 12 to 18 again, so 9 to 12 batch calls are in flight, and each refusal
     * refuses one call. A new pool starts at 4.
     */
    @Test
    void shouldSettleNearTheProvidersCapacityWithFewRefusals() {
        int capacity = 12;
        var limit = new LlmCallLimit(4, 0);
        boolean probing = true;
        int refusals = 0;
        int settledServed = 0;
        int settledRefused = 0;
        for (int round = 0; round < 2_000; round++) {
            int inFlight = limit.learned() - LlmCallLimit.reserve(limit.learned());
            if (inFlight > capacity) {
                refusals++;
                if (refusals > 1) {
                    assertThat(inFlight - capacity)
                            .as("a settled pool overshoots by one call")
                            .isEqualTo(1);
                    settledRefused++;
                    settledServed += capacity;
                }
                limit = limit.afterThrottle();
                probing = false;
                continue;
            }
            if (!probing) {
                assertThat(inFlight).as("calls in flight once settled").isBetween(capacity * 3 / 4, capacity);
                settledServed += inFlight;
            }
            for (int call = 0; call < inFlight; call++) limit = limit.afterServedCall(probing);
        }
        // One cycle serves 12 + 13 + ... + 17 = 87 calls while it climbs, and 12 beside the one refused call.
        assertThat(settledRefused).isPositive();
        assertThat(settledRefused * (87 + 12)).isLessThanOrEqualTo(settledServed + 87 + 12);
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
