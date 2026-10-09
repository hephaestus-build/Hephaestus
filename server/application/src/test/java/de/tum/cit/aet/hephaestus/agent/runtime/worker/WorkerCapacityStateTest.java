package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class WorkerCapacityStateTest extends BaseUnitTest {

    @Test
    void reviewClaimReleaseSnapshotFlow() {
        WorkerCapacityState state = new WorkerCapacityState(WorkerPropertiesFixtures.minimal("3", "1"));
        assertThat(state.reviewMax()).isEqualTo(3);
        assertThat(state.snapshot().spareReview()).isEqualTo(3);

        state.claimReview();
        state.claimReview();
        CapacityReport snap = state.snapshot();
        assertThat(snap.inFlightReview()).isEqualTo(2);
        assertThat(snap.spareReview()).isEqualTo(1);

        state.releaseReview();
        assertThat(state.snapshot().inFlightReview()).isEqualTo(1);
    }

    @Test
    void releaseUnderflowIsIdempotent() {
        // Double-release happens on the executor's cleanup path during early shutdown.
        WorkerCapacityState state = new WorkerCapacityState(WorkerPropertiesFixtures.minimal("1", "1"));
        state.releaseReview();
        assertThat(state.snapshot().inFlightReview()).isZero();
    }

    @Test
    void snapshotClampsSpareToZeroWhenInFlightExceedsMax() {
        // Defensive: claim/release accounting drift must never surface a negative spare count.
        WorkerCapacityState state = new WorkerCapacityState(WorkerPropertiesFixtures.minimal("1", "0"));
        state.claimReview();
        state.claimReview();
        CapacityReport snap = state.snapshot();
        assertThat(snap.spareReview()).isZero();
    }
}
