package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewBatchLifecycleTest extends BaseUnitTest {

    @Test
    void shouldPauseResumeAndCompleteABatchWhenEachMoveIsLegal() {
        var batch = new ReviewBatch();
        batch.pause(ReviewBatchPauseReason.PAUSED_BY_ADMIN);
        batch.resume();
        batch.resume();
        batch.pause(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
        batch.pause(ReviewBatchPauseReason.REVIEW_MODEL_UNBOUND);
        assertThat(batch.getPauseReason()).isEqualTo(ReviewBatchPauseReason.REVIEW_MODEL_UNBOUND);

        batch.complete(Instant.EPOCH);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.COMPLETED);
        assertThat(batch.getPauseReason()).isNull();
        assertThat(batch.getFinishedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void shouldEndAPausedScheduledBatchAtItsSpentCapWithoutAPauseReason() {
        var batch = new ReviewBatch();
        batch.resume();
        batch.pause(ReviewBatchPauseReason.BUDGET_EXHAUSTED);

        batch.exhaustBudget(Instant.EPOCH);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.BUDGET_EXHAUSTED);
        assertThat(batch.getPauseReason()).isNull();
    }

    @Test
    void shouldRefuseToCompleteOrExhaustABatchThatWasNeverConfirmed() {
        var batch = new ReviewBatch();

        assertThatIllegalStateException().isThrownBy(() -> batch.complete(Instant.EPOCH));
        assertThatIllegalStateException().isThrownBy(() -> batch.exhaustBudget(Instant.EPOCH));
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.AWAITING_CONFIRMATION);
        assertThat(batch.getFinishedAt()).isNull();
    }

    @Test
    void shouldRefuseEveryMoveWhenTheBatchHasEnded() {
        var batch = new ReviewBatch();
        batch.cancel(Instant.EPOCH);

        assertThatIllegalStateException().isThrownBy(batch::resume);
        assertThatIllegalStateException().isThrownBy(() -> batch.pause(ReviewBatchPauseReason.WORKSPACE_UNAVAILABLE));
        assertThatIllegalStateException().isThrownBy(() -> batch.complete(Instant.now()));
        assertThatIllegalStateException().isThrownBy(() -> batch.cancel(Instant.now()));
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.CANCELLED);
        assertThat(batch.getPauseReason()).isNull();
        assertThat(batch.getFinishedAt()).isEqualTo(Instant.EPOCH);
    }
}
