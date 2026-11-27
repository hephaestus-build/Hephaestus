package de.tum.cit.aet.hephaestus.practices.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReviewStageTest extends BaseUnitTest {

    @ParameterizedTest
    @CsvSource({
        // status, continuing, unsettled items, checked items, the stage shown
        "QUEUED, false, 3, 0, QUEUED",
        "QUEUED, true, 3, 0, CONTINUING",
        // A reused observation beside open practices is not a seal.
        "QUEUED, false, 2, 1, QUEUED",
        "RUNNING, false, 3, 0, CHECKING",
        "RUNNING, false, 1, 2, CHECKING",
        // Nothing open and nothing checked: the review has no observation to announce.
        "RUNNING, false, 0, 0, CHECKING",
        "RUNNING, false, 0, 3, OBSERVATIONS_READY",
        "QUEUED, false, 0, 3, OBSERVATIONS_READY",
        "COMPLETE, false, 0, 3, READY",
        "PARTIAL, false, 0, 1, PARTLY_CHECKED",
        "PARTIAL, false, 0, 0, WITHHELD",
        "FAILED, false, 0, 0, FAILED",
        "CANCELLED, false, 1, 1, STOPPED",
    })
    void shouldShowOnlyTheStateThatTheStoredReviewHasReached(
            ReviewStatus status, boolean continuing, long unsettled, long checked, ReviewStage expected) {
        assertThat(ReviewStage.of(status, continuing, unsettled, checked)).isEqualTo(expected);
    }
}
