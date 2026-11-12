package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayDeque;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LifecycleContractTest extends BaseUnitTest {
    @ParameterizedTest
    @EnumSource(
            value = ReviewStatus.class,
            names = {"COMPLETE", "PARTIAL", "FAILED", "CANCELLED"})
    void shouldGiveATerminalReviewNoExit(ReviewStatus terminal) {
        for (var next : ReviewStatus.values())
            assertThat(terminal.canTransitionTo(next)).isFalse();
    }

    @Test
    void shouldReachEveryReviewStateFromItsInitialState() {
        var reached = EnumSet.of(ReviewStatus.QUEUED);
        var pending = new ArrayDeque<ReviewStatus>();
        pending.add(ReviewStatus.QUEUED);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            for (var next : ReviewStatus.values()) {
                if (current.canTransitionTo(next) && reached.add(next)) pending.add(next);
            }
        }
        assertThat(reached).containsExactlyInAnyOrder(ReviewStatus.values());
    }
}
