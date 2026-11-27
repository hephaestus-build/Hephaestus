package de.tum.cit.aet.hephaestus.practices.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReviewStageTest extends BaseUnitTest {

    @ParameterizedTest
    @CsvSource({
        // status, practices ended, continuing, unsettled items, checked items, withheld items, the stage shown
        "QUEUED, 0, false, 3, 0, 0, QUEUED",
        "QUEUED, 1, true, 3, 0, 0, CONTINUING",
        // Every practice ended and staged: nothing waits to start.
        "QUEUED, 3, false, 3, 0, 0, CHECKING",
        // A reused observation beside open practices is not a seal.
        "QUEUED, 0, false, 2, 1, 0, QUEUED",
        "RUNNING, 0, false, 3, 0, 0, CHECKING",
        "RUNNING, 2, false, 1, 2, 0, CHECKING",
        // Nothing open and nothing checked: the review has no observation to announce.
        "RUNNING, 3, false, 0, 0, 3, CHECKING",
        "RUNNING, 3, false, 0, 3, 0, OBSERVATIONS_READY",
        "QUEUED, 3, false, 0, 3, 0, OBSERVATIONS_READY",
        "COMPLETE, 3, false, 0, 3, 0, READY",
        "PARTIAL, 1, false, 0, 1, 2, PARTLY_CHECKED",
        "PARTIAL, 1, false, 2, 1, 0, PARTLY_CHECKED",
        // Partial for another reason, with every practice checked.
        "PARTIAL, 3, false, 0, 3, 0, READY",
        "PARTIAL, 0, false, 0, 0, 3, WITHHELD",
        "FAILED, 0, false, 0, 0, 0, FAILED",
        "CANCELLED, 1, false, 1, 1, 0, STOPPED",
    })
    void shouldShowOnlyTheStateThatTheStoredReviewHasReached(
            ReviewStatus status,
            long ended,
            boolean continuing,
            long unsettled,
            long checked,
            long withheld,
            ReviewStage expected) {
        assertThat(ReviewStage.of(status, ended, continuing, unsettled, checked, withheld))
                .isEqualTo(expected);
    }
}
