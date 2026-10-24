package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueJob;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingStage;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingSums;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewOperationalViewsIntegrationTest extends BaseIntegrationTest {
    private static final Instant FROM = Instant.parse("2026-03-10T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-03-13T00:00:00Z");
    private static final String RANGE = "?from=2026-03-10T00:00:00Z&to=2026-03-13T00:00:00Z";

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    WebTestClient http;

    @MockitoBean
    ReviewRuntimeQuery runtime;

    ReviewIntegrationData.Scenario scenario;

    long scenarioAccountId;

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        scenarioAccountId = Objects.requireNonNull(scenario.account().getId());
    }

    private WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri("/workspaces/" + scenario.workspace().getWorkspaceSlug() + "/practices/reviews" + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Test
    void shouldCountActualProductRowsWithinTheHalfOpenRangeAndWorkspace() {
        var before = data.result(scenario, ResultOutcome.MET, FROM.minusSeconds(1));
        data.end(before, FROM);
        var first = data.result(scenario, ResultOutcome.MET, FROM);
        data.end(first, FROM.plusSeconds(3600));
        var second = data.result(scenario, ResultOutcome.NOT_MET, FROM.plusSeconds(3600));
        data.end(second, TO);
        data.mark(second, ResultMarkKind.INCORRECT, scenarioAccountId);
        data.result(scenario, ResultOutcome.COULD_NOT_DETERMINE, TO);
        data.result(data.scenario(), ResultOutcome.DOES_NOT_APPLY, FROM);
        get("/overview" + RANGE)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviews.complete")
                .isEqualTo(2)
                .jsonPath("$.results.met")
                .isEqualTo(1)
                .jsonPath("$.results.notMet")
                .isEqualTo(1)
                .jsonPath("$.results.doesNotApply")
                .isEqualTo(0)
                .jsonPath("$.results.couldNotDetermine")
                .isEqualTo(0)
                .jsonPath("$.resultsMarkedIncorrect")
                .isEqualTo(1)
                .jsonPath("$.practices.length()")
                .isEqualTo(1)
                .jsonPath("$.buckets[0].reviews")
                .isEqualTo(2);
    }

    @Test
    void shouldCountAllEightFeedbackStatesOnceAndBindPracticeCounts() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, FROM.plusSeconds(3600));
        for (var state : FeedbackState.values()) feedback(result, state);
        get("/overview" + RANGE)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback.awaitingApproval")
                .isEqualTo(1)
                .jsonPath("$.feedback.ready")
                .isEqualTo(1)
                .jsonPath("$.feedback.delivered")
                .isEqualTo(1)
                .jsonPath("$.feedback.unconfirmed")
                .isEqualTo(1)
                .jsonPath("$.feedback.withheld")
                .isEqualTo(1)
                .jsonPath("$.feedback.rejected")
                .isEqualTo(1)
                .jsonPath("$.feedback.replaced")
                .isEqualTo(1)
                .jsonPath("$.feedback.failed")
                .isEqualTo(1)
                .jsonPath("$.practices[0].feedback.ready")
                .isEqualTo(1)
                .jsonPath("$.buckets[0].feedback")
                .isEqualTo(8);
    }

    private void feedback(Result result, FeedbackState state) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO"
                        + " feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at,stop_reason)"
                        + " VALUES(:id,:ws,:review,:item,:developer,'PRACTICE_PAGE',:state,'Confirmed practice"
                        + " feedback.',:at,:delivered,:stop)")
                .param("id", id)
                .param("ws", result.getWorkspaceId())
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", result.getDeveloperId())
                .param("state", state.name())
                .param("at", Timestamp.from(result.getRecordedAt()))
                .param("delivered", state == FeedbackState.DELIVERED ? Timestamp.from(result.getRecordedAt()) : null)
                .param("stop", state == FeedbackState.WITHHELD ? "COMPOSER_WITHHELD" : null)
                .update();
        jdbc.sql("INSERT INTO feedback_basis(id,workspace_id,feedback_id,developer_id,result_id,ordinal)"
                        + " VALUES(:id,:ws,:feedback,:developer,:result,0)")
                .param("id", UUID.randomUUID())
                .param("ws", result.getWorkspaceId())
                .param("feedback", id)
                .param("developer", result.getDeveloperId())
                .param("result", result.getId())
                .update();
    }

    @Test
    void shouldUseTheRequestedTimeZoneForBucketMidnights() {
        data.result(scenario, ResultOutcome.MET, FROM.plusSeconds(3600));
        get("/overview" + RANGE + "&zone=Europe/Berlin")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.buckets[0].start")
                .isEqualTo("2026-03-09T23:00:00Z");
    }

    @Test
    void shouldRejectAnUnknownBucketZone() {
        get("/overview" + RANGE + "&zone=Not/AZone")
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldExposeRealQueueReferencesAndTheEstablishedCompletionRate() {
        var result = data.result(scenario, ResultOutcome.MET, FROM);
        var jobId = data.job(result);
        jdbc.sql("UPDATE review SET status='RUNNING',completed_at=NULL WHERE workspace_id=:ws AND id=:id")
                .param("ws", result.getWorkspaceId())
                .param("id", result.getReviewId())
                .update();
        jdbc.sql("UPDATE review_job SET status='QUEUED',processing_status='NONE',completed_at=NULL WHERE"
                        + " workspace_id=:ws AND id=:id")
                .param("ws", result.getWorkspaceId())
                .param("id", jobId)
                .update();
        when(runtime.queue(eq(scenario.workspace().getId()), any(), eq(5)))
                .thenReturn(new ReviewQueueSnapshot(
                        FROM,
                        2,
                        1,
                        3,
                        FROM.minusSeconds(20),
                        1,
                        List.of(new ReviewQueueJob(
                                jobId,
                                result.getReviewId(),
                                result.getItemId(),
                                result.getDeveloperId(),
                                ReviewJobStatus.QUEUED,
                                true,
                                FROM)),
                        4,
                        48));
        get("/queue")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.running")
                .isEqualTo(2)
                .jsonPath("$.activeWorkers")
                .isEqualTo(1)
                .jsonPath("$.waiting")
                .isEqualTo(3)
                .jsonPath("$.heldForBudget")
                .isEqualTo(1)
                .jsonPath("$.finishedPerHourLastDay")
                .isEqualTo(2.0)
                .jsonPath("$.head[0].reviewId")
                .isEqualTo(result.getReviewId().toString())
                .jsonPath("$.head[0].item.id")
                .isEqualTo(Long.toString(result.getItemId()));
    }

    @Test
    void shouldExposeAnEmptyQueueWithRequiredNullWaitingClock() {
        when(runtime.queue(eq(scenario.workspace().getId()), any(), eq(5)))
                .thenReturn(new ReviewQueueSnapshot(FROM, 0, 0, 0, null, 0, List.of(), 0, 0));
        get("/queue")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.waiting")
                .isEqualTo(0)
                .jsonPath("$.head.length()")
                .isEqualTo(0)
                .jsonPath("$.oldestWaitingSince")
                .value(value -> assertThat(value).isNull());
    }

    @Test
    void shouldKeepPublicAndFinishedTimingPopulationsSeparateOnTheWire() {
        when(runtime.timing(eq(scenario.workspace().getId()), eq(FROM), eq(TO), any()))
                .thenReturn(new ReviewTimingSnapshot(
                        Map.of(
                                "ASKED",
                                new ReviewTimingStage(2, 10.0, 20.0),
                                "TOTAL",
                                new ReviewTimingStage(1, 100.0, 100.0),
                                "FINISHED",
                                new ReviewTimingStage(2, 200.0, 300.0)),
                        new ReviewTimingSums(10, 20, 30, 20, 0, 20.0),
                        new ReviewTimingSums(20, 20, 60, 80, 20, null),
                        3,
                        2,
                        1,
                        4));
        get("/timing" + RANGE)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.asked.medianSeconds")
                .isEqualTo(10.0)
                .jsonPath("$.total.count")
                .isEqualTo(1)
                .jsonPath("$.finished.count")
                .isEqualTo(2)
                .jsonPath("$.totalShares.posted")
                .isEqualTo(0.2)
                .jsonPath("$.finishedShares.runUnsplit")
                .isEqualTo(0.1)
                .jsonPath("$.waitingLong")
                .isEqualTo(3)
                .jsonPath("$.finishedAfterMerge")
                .isEqualTo(2)
                .jsonPath("$.finishedAfterMergeStartedByMerge")
                .isEqualTo(1)
                .jsonPath("$.feedbackWithheldAsMerged")
                .isEqualTo(4);
    }

    @Test
    void shouldUseTheFirstRetainedReviewForAllTimeTimingAfterRuntimeExpiry() {
        var oldest = data.result(scenario, ResultOutcome.MET, FROM.minusSeconds(500L * 86400));
        when(runtime.timing(eq(scenario.workspace().getId()), eq(oldest.getRecordedAt()), eq(TO), any()))
                .thenReturn(new ReviewTimingSnapshot(Map.of(), null, null, 0, 0, 0, 0));
        get("/timing" + RANGE + "&allTime=true")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.from")
                .isEqualTo(oldest.getRecordedAt().toString())
                .jsonPath("$.asked.count")
                .isEqualTo(0)
                .jsonPath("$.finished.count")
                .isEqualTo(0);
    }

    @Test
    void shouldRequireWorkspaceAdministrationForEveryOperationalView() {
        jdbc.sql("UPDATE membership SET role='MEMBER' WHERE workspace_id=:ws AND account_id=:account")
                .param("ws", scenario.workspace().getId())
                .param("account", scenarioAccountId)
                .update();
        for (var path : List.of("/overview" + RANGE, "/queue", "/timing" + RANGE))
            get(path).expectStatus().isForbidden().expectBody(Void.class);
    }
}
