package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Closing a review closes what it never measured")
class ReviewClosureIntegrationTest extends AbstractResultAdmissionIntegrationTest {
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

    @Autowired
    ReviewStart starts;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MeterRegistry meters;

    @Test
    void shouldCountAReplacedReviewAndCloseItsMembersAndAssignments() {
        var scenario = data.scenario();
        var ordered = Instant.now().minusSeconds(60);
        var review = data.activeReview(
                scenario, Objects.requireNonNull(scenario.account().getId()), ReviewStatus.QUEUED, ordered, null);
        long workspace = review.getWorkspaceId();
        var ref = new ItemRef(
                workspace,
                review.getItemId(),
                scenario.item().getKind(),
                scenario.item().getSourceId(),
                null,
                null);
        var source = new SourceKind("scm.pull-request.core");
        jdbc.update(
                "UPDATE review_practice SET capture_decision=CAST(? AS jsonb) WHERE workspace_id=? AND review_id=?",
                "{\"sourceFingerprints\":{\"scm.pull-request.core\":\"" + "a".repeat(64) + "\"}}",
                workspace,
                review.getId());
        doReturn(Map.of(source, "b".repeat(64))).when(productItems).fingerprints(ref, Set.of(source), null);
        jdbc.update(
                "INSERT INTO item_event (id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,decided_at)"
                        + " VALUES (?,?,?,'scm.pull_request.synchronized','new-head',?,'WEBHOOK','RECORDED',?)",
                UUID.randomUUID(),
                workspace,
                review.getItemId(),
                Timestamp.from(ordered.plusSeconds(30)),
                Timestamp.from(ordered.plusSeconds(30)));
        double before = ended("cancelled", "replaced");

        var replaced = transactions.execute(ignored -> starts.replaceByNewerOccasion(workspace, review.getId()));
        assertThat(replaced).isTrue();

        assertThat(jdbc.queryForObject(
                        "SELECT status||'/'||stop_reason FROM review WHERE workspace_id=? AND id=?",
                        String.class,
                        workspace,
                        review.getId()))
                .isEqualTo("CANCELLED/REPLACED");
        assertThat(jdbc.queryForList(
                        "SELECT state||'/'||reason FROM review_item WHERE workspace_id=? AND review_id=?",
                        String.class,
                        workspace,
                        review.getId()))
                .containsExactly("WITHHELD/REPLACED");
        assertThat(jdbc.queryForList(
                        "SELECT status||'/'||reason FROM review_practice WHERE workspace_id=? AND review_id=?",
                        String.class,
                        workspace,
                        review.getId()))
                .containsExactly("WITHHELD/REPLACED");
        assertThat(ended("cancelled", "replaced")).isEqualTo(before + 1);
        var replacedAgain = transactions.execute(ignored -> starts.replaceByNewerOccasion(workspace, review.getId()));
        assertThat(replacedAgain).isFalse();
        assertThat(ended("cancelled", "replaced")).isEqualTo(before + 1);
    }

    private double ended(String status, String reason) {
        return meters.counter(PracticeMetrics.REVIEW_ENDED, "kind", "item", "status", status, "reason", reason)
                .count();
    }

    @Test
    void shouldCountACompleteReviewAndKeepItsMeasuredPracticeDone() {
        var scenario = data.scenario();
        var pending = admissions.pending(scenario, Instant.now(), null);
        var now = Instant.now();
        var kept = data.result(
                scenario,
                ResultOutcome.MET,
                now,
                now,
                Objects.requireNonNull(pending.review().getItemVersion()));
        jdbc.update(
                "UPDATE review_item SET state='REUSED',result_id=? WHERE workspace_id=? AND review_id=?",
                kept.getId(),
                pending.review().getWorkspaceId(),
                pending.review().getId());
        RuntimeJobTestData.updateJobs(
                jdbc,
                "UPDATE review_job SET status='COMPLETED',processing_status='DONE',completed_at=now() WHERE id=?",
                pending.jobId());
        double before = ended("complete", "none");

        reviews.complete(
                pending.review().getWorkspaceId(),
                pending.review().getId(),
                new ReviewCompletion(ReviewStatus.COMPLETE, null, now, now));

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM review WHERE workspace_id=? AND id=?",
                        String.class,
                        pending.review().getWorkspaceId(),
                        pending.review().getId()))
                .isEqualTo("COMPLETE");
        assertThat(jdbc.queryForList(
                        "SELECT status FROM review_practice WHERE workspace_id=? AND review_id=?",
                        String.class,
                        pending.review().getWorkspaceId(),
                        pending.review().getId()))
                .containsExactly("DONE");
        assertThat(jdbc.queryForList(
                        "SELECT state FROM review_item WHERE workspace_id=? AND review_id=?",
                        String.class,
                        pending.review().getWorkspaceId(),
                        pending.review().getId()))
                .containsExactly("REUSED");
        assertThat(ended("complete", "none")).isEqualTo(before + 1);
        reviews.complete(
                pending.review().getWorkspaceId(),
                pending.review().getId(),
                new ReviewCompletion(ReviewStatus.COMPLETE, null, now, now));
        assertThat(ended("complete", "none")).isEqualTo(before + 1);
    }

    @Test
    void shouldCountARefusedSelectionAndKeepEachWithholdingReason() {
        var scenario = data.scenario();
        long workspace = scenario.workspace().getId();
        var ref = new ItemRef(
                workspace,
                scenario.item().getId(),
                scenario.item().getKind(),
                scenario.item().getSourceId(),
                null,
                null);
        var version = new ItemVersion("closure-selection", null);
        var pinned = new ItemSnapshot(
                ref,
                version,
                new IntegrationRef(IntegrationKind.GITHUB, workspace, null),
                Map.of(),
                List.of(),
                new ItemLabel(ref.itemId(), "Work", "Work", "pull request", 1, null, null, IntegrationKind.GITHUB));
        doReturn(pinned).when(productItems).pin(ref, version);
        doReturn(true).when(budget).canRequest(workspace, ReviewKind.ITEM, null);
        doReturn(List.of(new BoundModelSlot("review", ModelKind.CHAT, null)))
                .when(models)
                .models(workspace, List.of());
        double before = ended("partial", "readiness_refused");

        var id = reviews.request(ReviewRequest.builder()
                .workspaceId(workspace)
                .itemId(ref.itemId())
                .kind(ReviewKind.ITEM)
                .origin(ReviewOrigin.REQUEST)
                .version(version)
                .requestedById(scenario.account().getId())
                .practiceIds(List.of(scenario.practice().getId()))
                .build());

        assertThat(jdbc.queryForObject(
                        "SELECT status||'/'||stop_reason FROM review WHERE workspace_id=? AND id=?",
                        String.class,
                        workspace,
                        id))
                .isEqualTo("PARTIAL/READINESS_REFUSED");
        assertThat(jdbc.queryForList(
                        "SELECT state||'/'||reason FROM review_item WHERE workspace_id=? AND review_id=?",
                        String.class,
                        workspace,
                        id))
                .containsExactly("WITHHELD/ROLE_UNAVAILABLE");
        assertThat(jdbc.queryForList(
                        "SELECT status||'/'||reason FROM review_practice WHERE workspace_id=? AND review_id=?",
                        String.class,
                        workspace,
                        id))
                .containsExactly("WITHHELD/ROLE_UNAVAILABLE");
        assertThat(ended("partial", "readiness_refused")).isEqualTo(before + 1);
    }

    @Test
    void shouldWithholdAReadyPracticeWhenItsRetryIsCancelledWhileQueued() {
        var pending = admissions.pending(data.scenario(), Instant.now(), null);
        // A lost attempt requeued: capture left the practice READY, and the job holds no claim.
        RuntimeJobTestData.updateJobs(
                jdbc,
                "UPDATE review_job SET status='QUEUED',completed_at=NULL,cancellation_reason=NULL,processing_status='NONE',started_at=NULL WHERE id=?",
                pending.jobId());
        transactions.executeWithoutResult(ignored -> starts.requeue(
                pending.review().getWorkspaceId(), pending.review().getId()));
        doReturn(Set.of(pending.jobId()))
                .when(runtime)
                .activeJobIds(
                        pending.review().getWorkspaceId(), pending.review().getId());

        double before = ended("cancelled", "cancelled");
        assertThat(reviews.cancel(
                        pending.review().getWorkspaceId(), pending.review().getId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM review WHERE workspace_id=? AND id=?",
                        String.class,
                        pending.review().getWorkspaceId(),
                        pending.review().getId()))
                .isEqualTo("QUEUED");
        assertThat(ended("cancelled", "cancelled")).isEqualTo(before);
        doReturn(Set.of())
                .when(runtime)
                .activeJobIds(
                        pending.review().getWorkspaceId(), pending.review().getId());
        completion.tick();

        assertClosedWithheld(pending, "CANCELLED");
        assertThat(ended("cancelled", "cancelled")).isEqualTo(before + 1);
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

        double before = ended("cancelled", "cancelled");
        assertThat(reviews.cancel(
                        pending.review().getWorkspaceId(), pending.review().getId()))
                .isTrue();

        assertClosedWithheld(pending, "CANCELLED");
        assertThat(ended("cancelled", "cancelled")).isEqualTo(before + 1);
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

        double before = ended("partial", "execution_failed");
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
        assertThat(ended("partial", "execution_failed")).isEqualTo(before + 1);
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
