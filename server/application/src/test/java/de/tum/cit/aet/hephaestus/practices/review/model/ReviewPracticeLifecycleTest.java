package de.tum.cit.aet.hephaestus.practices.review.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class ReviewPracticeLifecycleTest extends BaseUnitTest {

    @Test
    void shouldKeepTheFirstReasonWhenAWithheldPracticeIsWithheldAgain() {
        var practice = new ReviewPractice();
        practice.withhold("ROLE_UNAVAILABLE");
        practice.withhold("READINESS_REFUSED");

        assertThat(practice.getStatus()).isEqualTo(ReviewPracticeStatus.WITHHELD);
        assertThat(practice.getReason()).isEqualTo("ROLE_UNAVAILABLE");
    }

    @Test
    void shouldKeepTheFirstReasonWhenCaptureDecidesTheSameStateAgain() {
        var practice = new ReviewPractice();
        practice.decideCapture(ReviewPracticeStatus.WITHHELD, "source_contract_unsupported");
        practice.decideCapture(ReviewPracticeStatus.WITHHELD, "READINESS_REFUSED");

        assertThat(practice.getReason()).isEqualTo("source_contract_unsupported");
    }

    @Test
    void shouldDecideARunningPracticeReadyAgainOnARetriedCapture() {
        var practice = new ReviewPractice();
        practice.decideCapture(ReviewPracticeStatus.READY, null);
        practice.start();
        practice.decideCapture(ReviewPracticeStatus.READY, null);

        assertThat(practice.getStatus()).isEqualTo(ReviewPracticeStatus.READY);
    }

    @Test
    void shouldRefuseToMoveAFinishedPractice() {
        var practice = new ReviewPractice();
        practice.start();
        practice.finish();

        assertThatThrownBy(() -> practice.withhold("REPLACED")).isInstanceOf(IllegalStateException.class);
        assertThat(practice.getStatus()).isEqualTo(ReviewPracticeStatus.DONE);
        assertThat(practice.getReason()).isNull();
    }
}
