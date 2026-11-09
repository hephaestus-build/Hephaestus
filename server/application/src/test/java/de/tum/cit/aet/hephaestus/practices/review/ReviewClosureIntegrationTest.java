package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("Closing a review closes what it never measured")
class ReviewClosureIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ReviewService reviews;

    @Autowired
    ReviewJobCompletionService completion;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void shouldWithholdAReadyPracticeWhenItsRetryIsCancelledWhileQueued() {
        var pending = admissions.pending(data.scenario(), Instant.now(), null);
        // A lost attempt requeued: capture left the practice READY, and the job holds no claim.
        RuntimeJobTestData.updateJobs(
                jdbc,
                "UPDATE review_job SET status='QUEUED',processing_status='NONE',started_at=NULL WHERE id=?",
                pending.jobId());

        assertThat(reviews.cancel(
                        pending.review().getWorkspaceId(), pending.review().getId(), null))
                .isTrue();
        completion.tick();

        assertClosedWithheld(pending, "CANCELLED");
    }

    @Test
    void shouldWithholdAQueuedPracticeWhenAReviewWithoutAJobIsCancelled() {
        var pending = admissions.pending(data.scenario(), Instant.now(), null);
        jdbc.update(
                "UPDATE review_item SET job_id=NULL WHERE review_id=?",
                pending.review().getId());
        jdbc.update("DELETE FROM review_job WHERE id=?", pending.jobId());
        jdbc.update(
                "UPDATE review_practice SET status='QUEUED' WHERE review_id=?",
                pending.review().getId());

        assertThat(reviews.cancel(
                        pending.review().getWorkspaceId(), pending.review().getId(), null))
                .isTrue();

        assertClosedWithheld(pending, "CANCELLED");
    }

    @Test
    void shouldKeepAMeasuredPracticeDoneWhenItsReviewClosesWithAnUnmeasuredOne() {
        var scenario = data.scenario();
        var pending = admissions.pending(scenario, Instant.now(), null);
        var id = pending.review().getId();
        var now = Instant.now();
        var kept = data.result(
                scenario,
                ResultOutcome.MET,
                now,
                now,
                Objects.requireNonNull(pending.review().getItemVersion()));
        jdbc.update(
                "UPDATE review_item SET state='REUSED',result_id=? WHERE review_id=? AND practice_id=?",
                kept.getId(),
                id,
                scenario.practice().getId());
        admissions.withheld(pending, "TO_BE_RESET");
        jdbc.update("UPDATE review_item SET state='LISTED',reason=NULL WHERE review_id=? AND state='WITHHELD'", id);
        jdbc.update(
                "UPDATE review_practice SET status='READY',reason=NULL WHERE review_id=? AND status='WITHHELD'", id);
        RuntimeJobTestData.updateJobs(
                jdbc,
                "UPDATE review_job SET status='FAILED',processing_status='FAILED',completed_at=now() WHERE id=?",
                pending.jobId());

        reviews.complete(
                pending.review().getWorkspaceId(),
                id,
                new ReviewCompletion(
                        ReviewStatus.PARTIAL, ReviewStopReason.EXECUTION_FAILED, Instant.now(), Instant.now()));

        assertThat(jdbc.queryForList(
                        "SELECT p.status||'/'||i.state||'/'||COALESCE(i.reason,'-') FROM review_practice p JOIN review_item i"
                                + " ON i.review_id=p.review_id AND i.practice_id=p.practice_id WHERE p.review_id=?",
                        String.class,
                        id))
                .containsExactlyInAnyOrder("DONE/REUSED/-", "WITHHELD/WITHHELD/EXECUTION_FAILED");
    }

    private void assertClosedWithheld(ReviewAdmissionData.Pending pending, String reason) {
        var id = pending.review().getId();
        assertThat(jdbc.queryForObject("SELECT status FROM review WHERE id=?", String.class, id))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                        "SELECT status||':'||COALESCE(reason,'-') FROM review_practice WHERE review_id=?",
                        String.class,
                        id))
                .isEqualTo("WITHHELD:" + reason);
        assertThat(jdbc.queryForObject(
                        "SELECT state||':'||COALESCE(reason,'-') FROM review_item WHERE review_id=?", String.class, id))
                .isEqualTo("WITHHELD:" + reason);
    }
}
