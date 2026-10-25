package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewPractice;
import de.tum.cit.aet.hephaestus.practices.scan.ScanReportTestData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.trace.ItemTraceQueryService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewSummaryControllerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ResultMarkService marks;

    @Autowired
    WebTestClient http;

    @Autowired
    SqlReadMeasurement reads;

    @Autowired
    ReviewSummaryQueryService summaries;

    @Autowired
    ItemTraceQueryService traces;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    ReviewPracticeRepository assignments;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ScanReportTestData scans;

    @Autowired
    PracticeModelTestFixtures fixtures;

    @Autowired
    ItemRepository items;

    Scenario scenario;

    long scenarioAccountId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        scenarioAccountId = Objects.requireNonNull(scenario.account().getId());
    }

    String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri(root() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Test
    void shouldReadAPageOfReviewsAndTheirWorksTraceInAFixedNumberOfStatementsWhateverTheirPracticesAndRuns() {
        // The scan scenario's practice also scans files, so the page can hold a scan beside the reviews of the work.
        var scanned = scans.scenario();
        scenario = scanned.owner();
        scenarioAccountId = Objects.requireNonNull(scenario.account().getId());
        var alongside = List.of(practice(), practice());
        List<Result> recorded = new ArrayList<>();
        Map<UUID, UUID> jobOfReview = new HashMap<>();
        Map<UUID, UUID> eventOfReview = new HashMap<>();
        IntConsumer reviews = count -> {
            for (int review = 0; review < count; review++) {
                var at = Instant.now();
                // Each new review of the work replaces the claim the previous one held, as the store does.
                if (!recorded.isEmpty()) data.end(recorded.getLast(), at);
                var result =
                        data.result(scenario, recorded.size() % 2 == 0 ? ResultOutcome.MET : ResultOutcome.NOT_MET, at);
                // The first reviews pin one practice alongside, the later ones two.
                pinAlongside(result, recorded.size() < 2 ? alongside.subList(0, 1) : alongside);
                jobOfReview.put(result.getReviewId(), scriptRun(result));
                // Every other review starts from an event of the work; the others keep their requester.
                if (recorded.size() % 2 == 0) eventOfReview.put(result.getReviewId(), startedByEvent(result));
                fixtures.feedback(result, FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, "Recorded feedback");
                recorded.add(result);
            }
        };
        long ws = scenario.workspace().getId();
        long itemId = Objects.requireNonNull(scenario.item().getId());
        var everything = new ReviewFilterParams(null, null, null, null, null, null, null, null, null, null, null);

        var scan = scans.scan(scanned, ReviewStatus.COMPLETE, Instant.now());
        scans.result(scanned, scan);
        reviews.accept(2);
        var fewList = reads.measure(() -> summaries.list(ws, everything, PageRequest.of(0, 100)));
        var fewTrace = reads.measure(() -> traces.trace(ws, itemId));
        reviews.accept(8);
        var onOtherFile = otherFile(scanned);
        var laterScan = scans.scan(onOtherFile, ReviewStatus.COMPLETE, Instant.now());
        scans.result(onOtherFile, laterScan);
        var manyList = reads.measure(() -> summaries.list(ws, everything, PageRequest.of(0, 100)));
        var manyTrace = reads.measure(() -> traces.trace(ws, itemId));

        assertThat(fewList.value().getContent()).hasSize(3);
        assertThat(manyList.value().getContent()).hasSize(12);
        for (var eachScan : List.of(scan, laterScan)) {
            var scanSummary = manyList.value().getContent().stream()
                    .filter(s -> s.id().equals(eachScan.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(scanSummary.results().notMet()).isOne();
            assertThat(scanSummary.jobCount()).isOne();
        }
        for (int review = 0; review < recorded.size(); review++) {
            var result = recorded.get(review);
            var summary = manyList.value().getContent().stream()
                    .filter(s -> s.id().equals(result.getReviewId()))
                    .findFirst()
                    .orElseThrow();
            boolean met = result.getOutcome() == ResultOutcome.MET;
            assertThat(summary.practices()).hasSize(review < 2 ? 2 : 3);
            assertThat(summary.results().met()).isEqualTo(met ? 1 : 0);
            assertThat(summary.results().notMet()).isEqualTo(met ? 0 : 1);
            assertThat(summary.jobCount()).isEqualTo(1);
            assertThat(summary.feedback().delivered()).isOne();
            var event = eventOfReview.get(result.getReviewId());
            if (event == null) {
                assertThat(summary.startedBy()).isNull();
                assertThat(Objects.requireNonNull(summary.requestedBy()).id())
                        .isEqualTo(Long.toString(scenarioAccountId));
            } else {
                assertThat(Objects.requireNonNull(summary.startedBy()).id()).isEqualTo(event);
                assertThat(summary.requestedBy()).isNull();
            }
            var reviewed = summary.practices().stream()
                    .filter(practice ->
                            practice.practiceSlug().equals(scenario.practice().getSlug()))
                    .findFirst()
                    .orElseThrow();
            assertThat(reviewed.itemCounts().listed()).isOne();
            assertThat(reviewed.itemCounts().reviewed()).isOne();
            var script = Objects.requireNonNull(reviewed.script());
            assertThat(script.jobId()).isEqualTo(jobOfReview.get(result.getReviewId()));
            assertThat(script.models()).hasSize(1);
        }
        assertThat(manyTrace.value().reviews()).hasSize(10);
        assertThat(manyList.cost().statements()).isEqualTo(fewList.cost().statements());
        assertThat(manyTrace.cost().statements()).isEqualTo(fewTrace.cost().statements());
    }

    private Practice practice() {
        return versions.create(new PracticeVersionService.NewPractice(
                scenario.workspace(),
                "pinned-alongside-" + UUID.randomUUID().toString().substring(0, 8),
                scenario.item().getKind(),
                DefinitionTestFixtures.definition(),
                null,
                0,
                scenarioAccountId));
    }

    /** The review also pins {@code practices}, as a review of several practices on one work does. */
    private void pinAlongside(Result result, List<Practice> practices) {
        var first = assignments
                .findByWorkspaceIdAndReviewIdOrderByPracticeIdAsc(result.getWorkspaceId(), result.getReviewId())
                .getFirst();
        practices.forEach(practice -> assignments.saveAndFlush(ReviewPractice.builder()
                .workspaceId(result.getWorkspaceId())
                .reviewId(result.getReviewId())
                .practiceId(Objects.requireNonNull(practice.getId()))
                .practiceVersionId(practice.getCurrentVersion().getId())
                .status(first.getStatus())
                .itemsListed(1)
                .listingComplete(true)
                .captureDecision(first.getCaptureDecision())
                .method(first.getMethod())
                .build()));
    }

    /** The scan scenario on another file of its repository, so a second scan holds a member of its own. */
    private ScanReportTestData.Scenario otherFile(ScanReportTestData.Scenario scanned) {
        var file = new Item();
        file.setWorkspaceId(scanned.repository().getWorkspaceId());
        file.setKind(new ItemKind("scm.file"));
        file.setParentId(scanned.repository().getId());
        file.setPath("src/Other.java");
        file.setCreatedAt(Instant.now().minusSeconds(7200));
        items.saveAndFlush(file);
        return new ScanReportTestData.Scenario(scanned.owner(), scanned.repository(), file);
    }

    /** The result's review starts from an opened event of its work instead of a request. */
    private UUID startedByEvent(Result result) {
        var event = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO item_event(id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,decided_at)
            VALUES(:id,:ws,:item,'scm.pull_request.opened',:version,:at,'WEBHOOK','STARTED',:at)
            """)
                .param("id", event)
                .param("ws", result.getWorkspaceId())
                .param("item", result.getItemId())
                .param("version", result.getItemVersion())
                .param("at", Timestamp.from(result.getRecordedAt()))
                .update();
        jdbc.sql("UPDATE review SET origin='EVENT',requested_by_id=NULL,event_id=:event WHERE id=:review")
                .param("event", event)
                .param("review", result.getReviewId())
                .update();
        return event;
    }

    /** A finished job of the result's review that ran the reviewed practice's script and called one model. */
    private UUID scriptRun(Result result) {
        var job = data.job(result);
        jdbc.sql("""
            INSERT INTO review_job_script_run(job_id,attempt,practice_id,workspace_id,practice_version_id,status,
                script_kind,finished_at)
            VALUES(:job,1,:practice,:ws,:version,'OK','BUILTIN',now())
            """)
                .param("job", job)
                .param("practice", result.getPracticeId())
                .param("ws", result.getWorkspaceId())
                .param("version", result.getPracticeVersionId())
                .update();
        jdbc.sql("""
            INSERT INTO review_job_script_usage(job_id,attempt,model_kind,practice_id,workspace_id,calls)
            VALUES(:job,1,'DECISION',:practice,:ws,1)
            """)
                .param("job", job)
                .param("practice", result.getPracticeId())
                .param("ws", result.getWorkspaceId())
                .update();
        return job;
    }

    @Test
    void summaryUsesDurableReviewIdentity() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        get("/practices/reviews/" + r.getReviewId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(r.getReviewId().toString())
                .jsonPath("$.item.id")
                .isEqualTo(Long.toString(r.getItemId()));
    }

    @Test
    void usageHasAnExplicitEmptyAttemptListAfterRuntimeExpiry() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        get("/practices/reviews/" + r.getReviewId() + "/usage")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.attempts.length()")
                .isEqualTo(0);
    }

    @Test
    void foreignReviewIsNotFound() {
        var r = data.result(data.scenario(), ResultOutcome.MET, Instant.now());
        get("/practices/reviews/" + r.getReviewId()).expectStatus().isNotFound().expectBody(Void.class);
    }

    @Test
    void shouldRetainPinnedPracticeAndDeveloperFactsAfterRuntimeExpiry() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        get("/practices/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(result.getReviewId().toString())
                .jsonPath("$.content[0].origin")
                .isEqualTo("REQUEST")
                .jsonPath("$.content[0].requestedBy.id")
                .isEqualTo(Long.toString(scenarioAccountId))
                .jsonPath("$.content[0].practices[0].practiceVersionId")
                .isEqualTo(Long.toString(Objects.requireNonNull(
                        scenario.practice().getCurrentVersion().getId())))
                .jsonPath("$.content[0].practices[0].results.notMet")
                .isEqualTo(1)
                .jsonPath("$.content[0].jobCount")
                .isEqualTo(0);
    }

    @Test
    void shouldOrderReviewsCreatedAtTheSameInstantByTheirStableIdentity() {
        var at = Instant.parse("2026-10-13T00:00:00Z");
        var first = data.result(scenario, ResultOutcome.MET, at);
        data.end(first, at);
        var second = data.result(scenario, ResultOutcome.NOT_MET, at);
        var ordered = List.of(
                        first.getReviewId().toString(), second.getReviewId().toString())
                .stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        get("/practices/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(ordered.getFirst())
                .jsonPath("$.content[1].id")
                .isEqualTo(ordered.getLast());
    }

    @Test
    void shouldListReviewsOldestFirstWhenSortIsOldest() {
        var older = data.result(scenario, ResultOutcome.MET, Instant.parse("2026-10-12T00:00:00Z"));
        data.end(older, Instant.parse("2026-10-13T00:00:00Z"));
        var newer = data.result(scenario, ResultOutcome.NOT_MET, Instant.parse("2026-10-14T00:00:00Z"));
        get("/practices/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(newer.getReviewId().toString());
        get("/practices/reviews?sort=OLDEST")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].id")
                .isEqualTo(older.getReviewId().toString())
                .jsonPath("$.content[1].id")
                .isEqualTo(newer.getReviewId().toString())
                .jsonPath("$.sort.sorted")
                .isEqualTo(true);
    }

    @Test
    void shouldListOnlyReviewsWhoseJobIsInTheQueueStateAsTheQueueCountsIt() {
        var at = Instant.parse("2026-10-20T00:00:00Z");
        // One current result per practice, item and developer: each review's result ends before the next.
        var waiting = data.result(scenario, ResultOutcome.MET, at);
        data.activeJob(waiting, "QUEUED", null);
        data.end(waiting, at.plusSeconds(30));
        var held = data.result(scenario, ResultOutcome.MET, at.plusSeconds(60));
        data.activeJob(held, "QUEUED", "BUDGET");
        data.end(held, at.plusSeconds(90));
        var running = data.result(scenario, ResultOutcome.MET, at.plusSeconds(120));
        data.activeJob(running, "RUNNING", null);
        data.end(running, at.plusSeconds(150));
        var finished = data.result(scenario, ResultOutcome.MET, at.plusSeconds(180));
        data.job(finished);
        get("/practices/reviews/queue")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.waiting")
                .isEqualTo(1)
                .jsonPath("$.heldForBudget")
                .isEqualTo(1)
                .jsonPath("$.running")
                .isEqualTo(1);
        for (var expected : List.of(
                List.of("WAITING", waiting.getReviewId().toString()),
                List.of("HELD", held.getReviewId().toString()),
                List.of("RUNNING", running.getReviewId().toString()))) {
            get("/practices/reviews?queueState=" + expected.getFirst())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].id")
                    .isEqualTo(expected.getLast());
        }
    }

    @Test
    void shouldExcludeIncorrectProofFromPracticeFiltersAndRestoreItWhenTheMarkIsRemoved() {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        var mark = data.mark(result, ResultMarkKind.INCORRECT, scenarioAccountId);
        String path = "/practices/reviews?practiceSlug=" + scenario.practice().getSlug();
        get(path).expectStatus().isOk().expectBody().jsonPath("$.totalElements").isEqualTo(0);
        marks.remove(
                scenario.workspace().getId(),
                result.getId(),
                mark.getId(),
                scenarioAccountId,
                "The recorded proof is correct.");
        get(path)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(result.getReviewId().toString());
    }

    @Test
    void listIsScopedToWorkspace() {
        data.result(scenario, ResultOutcome.MET, Instant.now());
        data.result(data.scenario(), ResultOutcome.NOT_MET, Instant.now());
        get("/practices/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1);
    }
}
