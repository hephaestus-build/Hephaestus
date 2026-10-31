package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class ReviewRequestRejectedTest extends BaseUnitTest {
    private static final String DETAIL = "The occasion kept no record of the work as it was.";

    @Test
    void shouldKeepTheSettledFailureAsCauseWithoutShowingItInTheProblemDetail() {
        var unavailable = new ItemCaptureException(
                ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE, "The occasion kept no record of the work: EXPIRED");

        var rejected = new ReviewRequestRejected(
                ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                DETAIL,
                ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE,
                unavailable);

        assertThat(rejected.getCause()).isSameAs(unavailable);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rejected.getBody().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(rejected.getBody().getDetail()).isEqualTo(DETAIL);
        assertThat(rejected.getBody().getProperties()).isNullOrEmpty();
        assertThat(rejected.reason()).isEqualTo(ReviewRequestRejected.Reason.ITEM_INELIGIBLE);
        assertThat(rejected.eventReason()).isEqualTo(ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE);
    }

    @Test
    void shouldHaveNoCauseWhenTheRequestIsRejectedOnItsOwn() {
        var rejected = new ReviewRequestRejected(
                ReviewRequestRejected.Reason.ITEM_INELIGIBLE, DETAIL, ReviewRequestReason.NO_ACTIVE_PRACTICE);

        assertThat(rejected.getCause()).isNull();
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rejected.getBody().getDetail()).isEqualTo(DETAIL);
        assertThat(rejected.eventReason()).isEqualTo(ReviewRequestReason.NO_ACTIVE_PRACTICE);
        assertThat(new ReviewRequestRejected(ReviewRequestRejected.Reason.SCAN_ACTIVE, DETAIL).getCause())
                .isNull();
    }

    @Test
    void shouldNameWhereToResumeWhenTheBudgetIsSpentAndCarryNoRetryWindow() {
        var rejected = ReviewRequestRejected.budgetUnavailable();

        assertThat(rejected.reason()).isEqualTo(ReviewRequestRejected.Reason.BUDGET_UNAVAILABLE);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getBody().getDetail())
                .isEqualTo("This workspace’s monthly AI budget does not allow another review now. A workspace admin"
                        + " can see why, and how to resume, under AI usage.");
        // A rate-limit 429 says when to try again; a spent budget is not a wait the client can count down.
        assertThat(rejected.getBody().getProperties()).isNullOrEmpty();
        assertThat(rejected.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER))
                .isFalse();
    }
}
