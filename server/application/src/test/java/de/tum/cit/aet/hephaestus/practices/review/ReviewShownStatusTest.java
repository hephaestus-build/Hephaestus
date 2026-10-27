package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("unit")
class ReviewShownStatusTest {

    private static final Map<ReviewJobStatus, Long> WAITING = Map.of(ReviewJobStatus.QUEUED, 1L);
    private static final Map<ReviewJobStatus, Long> CLAIMED = Map.of(ReviewJobStatus.RUNNING, 1L);

    @Test
    void shouldShowAStartedReviewAsQueuedWhenItsOnlyActiveJobWaits() {
        assertThat(ReviewShownStatus.of(ReviewStatus.RUNNING, WAITING)).isEqualTo(ReviewStatus.QUEUED);
        assertThat(ReviewShownStatus.of(
                        ReviewStatus.RUNNING, Map.of(ReviewJobStatus.QUEUED, 1L, ReviewJobStatus.FAILED, 2L)))
                .isEqualTo(ReviewStatus.QUEUED);
    }

    @Test
    void shouldShowAStartedReviewAsRunningWhenAWorkerRunsAJobOrNoneWaits() {
        assertThat(ReviewShownStatus.of(ReviewStatus.RUNNING, CLAIMED)).isEqualTo(ReviewStatus.RUNNING);
        assertThat(ReviewShownStatus.of(
                        ReviewStatus.RUNNING, Map.of(ReviewJobStatus.QUEUED, 1L, ReviewJobStatus.RUNNING, 1L)))
                .isEqualTo(ReviewStatus.RUNNING);
        assertThat(ReviewShownStatus.of(ReviewStatus.RUNNING, Map.of())).isEqualTo(ReviewStatus.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void shouldShowEveryOtherStoredStatusAsItIsWhateverItsJobs(ReviewStatus stored) {
        for (var jobs : List.of(Map.<ReviewJobStatus, Long>of(), WAITING, CLAIMED)) {
            assertThat(ReviewShownStatus.of(stored, jobs)).isEqualTo(stored);
        }
    }
}
