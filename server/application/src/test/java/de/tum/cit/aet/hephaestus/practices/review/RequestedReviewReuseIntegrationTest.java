package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.tenancy.WorkspaceStatementInspector;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAttemptGuard;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/** A requested review keeps the result of a practice whose version and captured sources are unchanged. */
class RequestedReviewReuseIntegrationTest extends BaseIntegrationTest {
    // The doubles of ScanReportIntegrationTest, which tests the scan side of this reuse: both share one Spring context.
    @MockitoBean
    PersonIdentityResolver people;

    @MockitoSpyBean
    WorkspaceStatementInspector statements;

    @MockitoBean
    EvidenceAuthorization authorization;

    @MockitoBean
    ReviewRuntimeQuery runtime;

    @MockitoBean
    ReviewModelBindings models;

    @MockitoBean
    ReviewBudget budget;

    @MockitoBean
    HistoryCustodyQuery custody;

    @MockitoBean
    ReviewAttemptGuard ownership;

    private static final String SAME = "a".repeat(64);

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ReviewAssignmentStore store;

    @Autowired
    ReusedResultQuery reusedResults;

    @Autowired
    ReviewItemRepository memberships;

    @Autowired
    ReviewPracticeRepository assignments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private ReviewIntegrationData.Scenario scenario;
    private Result earlier;

    @BeforeEach
    void anEarlierReviewRecordedAResult() {
        when(custody.find(anyLong(), anyList()))
                .thenAnswer(call -> new HistoryCustody(true, false, List.of(), List.of(), call.getArgument(1)));
        databaseTestUtils.cleanDatabase();
        when(authorization.permits(anyLong(), any(), any())).thenReturn(true);
        scenario = data.scenario();
        var recordedAt = Instant.now().minusSeconds(120);
        earlier = data.result(
                scenario,
                ResultOutcome.MET,
                recordedAt,
                recordedAt,
                "item-version-" + UUID.randomUUID(),
                new HostCoverage(
                        true,
                        List.of(),
                        1,
                        0,
                        0,
                        List.of(new ReadReceipt(
                                new SourceKind("scm.pull-request.diff"),
                                "inputs/diff",
                                "file",
                                "commit",
                                ReadReceiptStatus.COMPLETE,
                                SAME,
                                null,
                                null,
                                null))));
        jdbc.update("UPDATE review_item SET job_id=? WHERE review_id=?", data.job(earlier), earlier.getReviewId());
        jdbc.update(
                "UPDATE review_practice SET capture_decision=CAST(? AS jsonb) WHERE review_id=?",
                json.createObjectNode()
                        .put(ReviewCaptureDecision.SOURCES_DIGEST, SAME)
                        .toString(),
                earlier.getReviewId());
    }

    private ReviewAdmissionData.Pending requested(ReviewOrigin origin) {
        var pending = admissions.pending(
                scenario, Instant.now(), scenario.account().getId(), Objects.requireNonNull(earlier.getItemVersion()));
        admissions.running(pending, 0);
        when(runtime.runtime(pending.review().getWorkspaceId(), pending.review().getId()))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        pending.jobId(),
                        ReviewJobStatus.RUNNING,
                        0,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "test-trace")))));
        if (origin == ReviewOrigin.EVENT) {
            var event = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO item_event(id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,decided_at)"
                            + " VALUES(?,?,?,'scm.pull_request.opened',?,now(),'WEBHOOK','STARTED',now())",
                    event,
                    pending.review().getWorkspaceId(),
                    pending.review().getItemId(),
                    pending.review().getItemVersion());
            jdbc.update(
                    "UPDATE review SET origin='EVENT', requested_by_id=NULL, event_id=? WHERE id=?",
                    event,
                    pending.review().getId());
        }
        return pending;
    }

    private Set<Long> capture(ReviewAdmissionData.Pending pending, @Nullable String digest) {
        var facts = json.createObjectNode();
        if (digest != null) facts.put(ReviewCaptureDecision.SOURCES_DIGEST, digest);
        return store.capture(
                pending.review().getWorkspaceId(),
                pending.review().getId(),
                pending.jobId(),
                0,
                List.of(new ReviewCaptureDecision(
                        scenario.practice().getId(), ReviewPracticeStatus.READY, null, facts)));
    }

    private void assertMeasures(ReviewAdmissionData.Pending pending, Set<Long> reused) {
        assertThat(reused).isEmpty();
        var member = memberships
                .findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                        pending.review().getWorkspaceId(), pending.review().getId())
                .getFirst();
        assertThat(member.getState()).isEqualTo(ReviewItemState.LISTED);
        assertThat(member.getResultId()).isNull();
    }

    @Test
    void shouldKeepTheCurrentResultWhenItsCaptureReadTheSameSources() {
        var pending = requested(ReviewOrigin.REQUEST);

        assertThat(capture(pending, SAME)).containsExactly(scenario.practice().getId());

        long workspace = pending.review().getWorkspaceId();
        var member = memberships
                .findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                        workspace, pending.review().getId())
                .getFirst();
        assertThat(member.getState()).isEqualTo(ReviewItemState.REUSED);
        assertThat(member.getResultId()).isEqualTo(earlier.getId());
        assertThat(assignments
                        .findByWorkspaceIdAndReviewIdAndPracticeId(
                                workspace,
                                pending.review().getId(),
                                scenario.practice().getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(ReviewPracticeStatus.DONE);
        // It remains the earlier review's result: this review recorded nothing new.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM result WHERE review_id=?",
                        Long.class,
                        pending.review().getId()))
                .isZero();
        assertThat(reusedResults.of(workspace, pending.review().getId()))
                .extracting(Result::getId)
                .containsExactly(earlier.getId());
    }

    @Test
    void shouldMeasureAgainWhenASourceChangedAtTheSameVersion() {
        var pending = requested(ReviewOrigin.REQUEST);

        assertMeasures(pending, capture(pending, "b".repeat(64)));
    }

    @Test
    void shouldMeasureAgainWhenTheEarlierCaptureRecordedNoDigest() {
        jdbc.update("UPDATE review_practice SET capture_decision='{}'::jsonb WHERE review_id=?", earlier.getReviewId());
        var pending = requested(ReviewOrigin.REQUEST);

        assertMeasures(pending, capture(pending, SAME));
    }

    @Test
    void shouldMeasureAgainWhenTheResultEnded() {
        data.end(earlier, Instant.now());
        var pending = requested(ReviewOrigin.REQUEST);

        assertMeasures(pending, capture(pending, SAME));
    }

    @Test
    void shouldMeasureAgainWhenSomeoneMarkedTheResultIncorrect() {
        data.mark(
                earlier,
                ResultMarkKind.INCORRECT,
                Objects.requireNonNull(scenario.account().getId()));
        var pending = requested(ReviewOrigin.REQUEST);

        assertMeasures(pending, capture(pending, SAME));
    }

    @Test
    void shouldMeasureAgainOnAnOccasionEvenWhenNothingChanged() {
        var pending = requested(ReviewOrigin.EVENT);

        assertMeasures(pending, capture(pending, SAME));
    }
}
