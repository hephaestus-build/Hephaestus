package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class WorkerCapacityStateTest extends BaseUnitTest {

    @Test
    void reviewClaimReleaseFlow() {
        WorkerCapacityState state = new WorkerCapacityState(WorkerPropertiesFixtures.minimal("3"));
        assertThat(state.reviewMax()).isEqualTo(3);
        assertThat(state.spareReview()).isEqualTo(3);

        state.claimReview();
        state.claimReview();
        assertThat(state.inFlightReview()).isEqualTo(2);
        assertThat(state.spareReview()).isEqualTo(1);

        state.releaseReview();
        assertThat(state.inFlightReview()).isEqualTo(1);
    }

    @Test
    void releaseUnderflowIsIdempotent() {
        // Double-release happens on the executor's cleanup path during early shutdown.
        WorkerCapacityState state = new WorkerCapacityState(WorkerPropertiesFixtures.minimal("1"));
        state.releaseReview();
        assertThat(state.inFlightReview()).isZero();
    }

    @Test
    void spareClampsToZeroWhenInFlightExceedsMax() {
        // Defensive: claim/release accounting drift must never surface a negative spare count.
        WorkerCapacityState state = new WorkerCapacityState(WorkerPropertiesFixtures.minimal("1"));
        state.claimReview();
        state.claimReview();
        assertThat(state.spareReview()).isZero();
    }
}
