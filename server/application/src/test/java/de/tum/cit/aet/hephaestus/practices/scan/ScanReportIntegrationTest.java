package de.tum.cit.aet.hephaestus.practices.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeQueryAdapter;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.tenancy.WorkspaceStatementInspector;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemExclusionReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.adapter.PracticesPersonDataCatalog;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkService;
import de.tum.cit.aet.hephaestus.practices.result.ResultRetentionService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewOverviewService;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanMembershipQueries;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.scan.dto.ReusedScanReportItemDTO;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.RemovalGuardState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAttemptGuard;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanReuse;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFactsQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ScanExcludedItem;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ScanReportIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ScanReportTestData data;

    @Autowired
    ResultRetentionService retention;

    @Autowired
    ScanQueryService query;

    @Autowired
    ScanReportItemsService reportItems;

    @Autowired
    WebTestClient http;

    @Autowired
    ReviewScanStore store;

    @Autowired
    ReviewScanReuse reuse;

    @Autowired
    ReviewAssignmentStore assignments;

    @Autowired
    ReviewOverviewService overview;

    @Autowired
    ReviewService reviews;

    @Autowired
    ScanReportService reports;

    @Autowired
    ResultMarkService marks;

    @Autowired
    ItemRepository items;

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

    @Autowired
    PracticesPersonDataCatalog personData;

    @Autowired
    ReviewItemRepository membership;

    @Autowired
    JsonMapper json;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    PersonIdentityResolver people;

    @Autowired
    ReviewRuntimeRepository runtimeRows;

    @Autowired
    ReviewJobRepository jobs;

    @MockitoSpyBean
    WorkspaceStatementInspector statements;

    ScanReportTestData.Scenario scenario;

    long scenarioAccountId;

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        scenarioAccountId = Objects.requireNonNull(scenario.owner().account().getId());
        when(custody.find(anyLong(), anyList()))
                .thenAnswer(call -> new HistoryCustody(false, false, List.of(), List.of(), call.getArgument(1)));
        when(authorization.permitsAll(anyLong(), anyCollection(), any())).thenAnswer(call -> {
            long workspace = call.getArgument(0);
            Collection<Result> rows = call.getArgument(1);
            return rows.stream()
                    .filter(r -> r.getWorkspaceId() == workspace)
                    .map(Result::getId)
                    .collect(Collectors.toSet());
        });
        when(authorization.permits(anyLong(), any(), any()))
                .thenAnswer(call -> ((Result) call.getArgument(1)).getWorkspaceId() == (long) call.getArgument(0));
    }

    @Test
    void shouldReturnTheActiveScanAsAStandardProblemResponse() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        http.post()
                .uri("/workspaces/" + scenario.owner().workspace().getWorkspaceSlug() + "/practices/scans")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario.owner())))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of("repositoryItemId", scenario.repository().getId().toString(), "budgetUsd", "1.0000"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Conflict")
                .jsonPath("$.status")
                .isEqualTo(409)
                .jsonPath("$.activeScanId")
                .isEqualTo(scan.getId().toString());
    }

    @Test
    void shouldListScopedScanCountsAndRecheckCurrentEvidencePermission() {
        var scan = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(60));
        data.result(scenario, scan);
        var foreign = data.scenario();
        var foreignScan =
                data.scan(foreign, ReviewStatus.COMPLETE, Instant.now().minusSeconds(60));
        data.result(foreign, foreignScan);
        var page = query.list(scan.getWorkspaceId(), null, null, PageRequest.of(0, 20));
        assertThat(page.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(scan.getId());
            assertThat(summary.practiceCount()).isOne();
            assertThat(summary.counts()).isEqualTo(query.summary(scan).counts());
            assertThat(summary.counts().openProblems()).isEqualTo(2);
        });
        when(authorization.permitsAll(anyLong(), anyCollection(), any())).thenReturn(Set.of());
        assertThat(query.list(scan.getWorkspaceId(), null, null, PageRequest.of(0, 20))
                        .getContent())
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.counts().openProblems()).isZero();
                    assertThat(summary.counts().problems()).isEqualTo(2);
                });
    }

    @Test
    void shouldListAStartedScanWhoseInvocationWaitsAsQueuedInItsRowFilterAndDetail() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        invocation(scan.getWorkspaceId(), scan.getId(), ReviewJobStatus.QUEUED);
        var adapter = new ReviewRuntimeQueryAdapter(
                jobs, mock(ReviewAssignmentQuery.class), runtimeRows, mock(ReviewTimingFactsQuery.class));
        when(runtime.jobCounts(anyLong(), anyCollection()))
                .thenAnswer(call -> adapter.jobCounts(call.getArgument(0), call.getArgument(1)));

        assertThat(query.list(scan.getWorkspaceId(), null, ReviewStatus.QUEUED, PageRequest.of(0, 20))
                        .getContent())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(scan.getId());
                    assertThat(row.status()).isEqualTo(ReviewStatus.QUEUED);
                });
        assertThat(query.list(scan.getWorkspaceId(), null, ReviewStatus.RUNNING, PageRequest.of(0, 20))
                        .getContent())
                .isEmpty();
        assertThat(query.detail(scan.getWorkspaceId(), scan.getId()).summary().status())
                .isEqualTo(ReviewStatus.QUEUED);
    }

    @Test
    void shouldReadScanProgressAndReportsWithoutSelectingJobPayloads() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        var result = data.result(scenario, scan);
        var completed = Objects.requireNonNull(membership
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        scan.getWorkspaceId(), scan.getId(), result.getPracticeId(), result.getItemId())
                .orElseThrow()
                .getJobId());
        var active = invocation(scan.getWorkspaceId(), scan.getId(), ReviewJobStatus.RUNNING);
        var failed = invocation(scan.getWorkspaceId(), scan.getId(), ReviewJobStatus.FAILED);
        jdbc.sql("UPDATE review_job SET capture=jsonb_build_object('tree',repeat('x',1048576))"
                        + " WHERE workspace_id=:ws AND review_id=:review")
                .param("ws", scan.getWorkspaceId())
                .param("review", scan.getId())
                .update();
        var adapter = new ReviewRuntimeQueryAdapter(
                jobs, mock(ReviewAssignmentQuery.class), runtimeRows, mock(ReviewTimingFactsQuery.class));
        when(runtime.jobCounts(scan.getWorkspaceId(), List.of(scan.getId())))
                .thenAnswer(call -> adapter.jobCounts(scan.getWorkspaceId(), List.of(scan.getId())));
        when(runtime.usage(scan.getWorkspaceId(), scan.getId()))
                .thenAnswer(call -> adapter.usage(scan.getWorkspaceId(), scan.getId()));
        clearInvocations(statements);

        assertThat(adapter.runtime(scan.getWorkspaceId(), scan.getId())
                        .orElseThrow()
                        .jobs())
                .extracting(ReviewJobSnapshot::id)
                .containsExactlyInAnyOrder(active, failed, completed);
        assertThat(adapter.activeJobIds(scan.getWorkspaceId(), scan.getId())).containsExactly(active);
        assertThat(jobs.findActiveId(scan.getId(), scan.getWorkspaceId(), Set.of(ReviewJobStatus.RUNNING)))
                .contains(active);
        assertThat(adapter.jobCounts(scan.getWorkspaceId(), List.of(scan.getId()))
                        .get(scan.getId()))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(ReviewJobStatus.RUNNING, 1L, ReviewJobStatus.FAILED, 1L, ReviewJobStatus.COMPLETED, 1L));
        assertThat(adapter.jobCounts(scan.getWorkspaceId() + 1, List.of(scan.getId())))
                .isEmpty();
        assertThat(adapter.runtime(scan.getWorkspaceId() + 1, scan.getId())).isEmpty();
        assertThat(adapter.activeJobIds(scan.getWorkspaceId() + 1, scan.getId()))
                .isEmpty();
        var progress = query.detail(scan.getWorkspaceId(), scan.getId()).progress();
        assertThat(progress.activeJobs()).isOne();
        assertThat(progress.queuedJobs()).isZero();
        jdbc.sql("UPDATE review_job SET status='QUEUED' WHERE workspace_id=:ws AND id=:id")
                .param("ws", scan.getWorkspaceId())
                .param("id", active)
                .update();
        var queued = query.detail(scan.getWorkspaceId(), scan.getId()).progress();
        assertThat(queued.activeJobs()).isZero();
        assertThat(queued.queuedJobs()).isOne();
        assertThat(query.list(scan.getWorkspaceId(), null, null, PageRequest.of(0, 20))
                        .getContent())
                .extracting(summary -> summary.id())
                .contains(scan.getId());
        assertThat(query.report(scan.getWorkspaceId(), scan.getId()).counts().reviewed())
                .isOne();
        assertThat(reportItems
                        .items(
                                scan.getWorkspaceId(),
                                scan.getId(),
                                null,
                                20,
                                new ScanMembershipQueries.ItemFilter(null, null, null, null, null))
                        .content())
                .hasSize(1);
        assertThat(reportItems
                        .problems(
                                scan.getWorkspaceId(),
                                scan.getId(),
                                null,
                                20,
                                new ScanMembershipQueries.ProblemFilter(null, null, null, null, false))
                        .content())
                .hasSize(2);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(statements, atLeastOnce()).inspect(sql.capture());
        var jobReads = sql.getAllValues().stream()
                .filter(statement -> statement.contains("review_job"))
                .toList();
        assertThat(jobReads)
                .isNotEmpty()
                .allSatisfy(statement -> assertThat(statement)
                        .doesNotContainPattern("(?i)\\b(capture|input|config_snapshot|output)\\b")
                        .doesNotContain("select *"));
    }

    @Test
    void oneResultWithTwoProblemsHasOneReviewedPairAndTwoProblems() {
        var scan = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(60));
        data.result(scenario, scan);
        var report = query.report(scan.getWorkspaceId(), scan.getId());
        assertThat(report.counts().reviewed()).isOne();
        assertThat(report.counts().problems()).isEqualTo(2);
        var recorded = report.recordedCounts();
        assertThat(recorded).isNotNull();
        assertThat(recorded.problems()).isEqualTo(2);
        assertThat(report.problemChanges().added()).isEqualTo(2);
    }

    @Test
    void shouldExposeDurablePendingScanFactsAndRequireTheTenantLockTransaction() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        assertThat(store.active(100))
                .filteredOn(s -> s.review().id().equals(scan.getId()))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.inventoryRecorded()).isTrue();
                    assertThat(s.inventoryComplete()).isTrue();
                    assertThat(s.listedPracticeIds())
                            .containsExactly(scenario.owner().practice().getId());
                    assertThat(s.listed()).isOne();
                    assertThat(s.judged()).isZero();
                });
        assertThat(store.pending(scan.getWorkspaceId(), scan.getId(), 1))
                .extracting(s -> s.id().itemId())
                .containsExactly(scenario.file().getId());
        assertThatThrownBy(() -> store.lock(scan.getWorkspaceId(), scan.getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(store.lock(scan.getWorkspaceId(), scan.getId()).review().id())
                    .isEqualTo(scan.getId());
        });
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                        .executeWithoutResult(tx -> store.lock(scan.getWorkspaceId() + 1, scan.getId())))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void shouldExcludeLiveInvocationsAndRebindUnjudgedMembershipAfterTheOldInvocationEnds() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        var assignment = new ReviewAssignmentId(
                scan.getId(),
                scenario.owner().practice().getId(),
                scenario.file().getId());
        var first = invocation(scan.getWorkspaceId(), scan.getId(), ReviewJobStatus.QUEUED);
        assignments.bind(scan.getWorkspaceId(), scan.getId(), first, List.of(assignment));
        when(runtime.activeJobIds(scan.getWorkspaceId(), scan.getId())).thenReturn(Set.of(first));
        assertThat(store.pending(scan.getWorkspaceId(), scan.getId(), 10)).isEmpty();
        var next = invocation(scan.getWorkspaceId(), scan.getId(), ReviewJobStatus.FAILED);
        assertThatThrownBy(() -> assignments.bind(scan.getWorkspaceId(), scan.getId(), next, List.of(assignment)))
                .isInstanceOf(IllegalStateException.class);
        jdbc.sql("UPDATE review_job SET status='FAILED' WHERE workspace_id=:ws AND id=:job")
                .param("ws", scan.getWorkspaceId())
                .param("job", first)
                .update();
        jdbc.sql("UPDATE review_job SET status='QUEUED' WHERE workspace_id=:ws AND id=:job")
                .param("ws", scan.getWorkspaceId())
                .param("job", next)
                .update();
        when(runtime.activeJobIds(scan.getWorkspaceId(), scan.getId())).thenReturn(Set.of(next));
        assertThat(store.pending(scan.getWorkspaceId(), scan.getId(), 10))
                .extracting(s -> s.id())
                .containsExactly(assignment);
        assignments.bind(scan.getWorkspaceId(), scan.getId(), next, List.of(assignment));
        var bound = membership
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        scan.getWorkspaceId(), scan.getId(), assignment.practiceId(), assignment.itemId())
                .orElseThrow();
        assertThat(bound.getJobId()).isEqualTo(next);
        assertThat(bound.getResultId()).isNull();
    }

    private UUID invocation(long workspaceId, UUID reviewId, ReviewJobStatus status) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO"
                        + " review_job(id,review_id,workspace_id,status,attempt,processing_status,trace_id,input,config_snapshot,submitted_at,available_at,started_at,completed_at)"
                        + " VALUES(:id,:review,:ws,:status,0,'PENDING',:trace,CAST(:input AS jsonb),'{}'::jsonb,:at,:at,CASE WHEN"
                        + " :status='QUEUED' THEN NULL ELSE CAST(:at AS timestamptz) END,CASE WHEN :status='FAILED' THEN CAST(:at"
                        + " AS timestamptz) ELSE NULL END)")
                .param("id", id)
                .param("review", reviewId)
                .param("ws", workspaceId)
                .param("status", status.name())
                .param(
                        "input",
                        json.writeValueAsString(Map.of(
                                "itemId",
                                scenario.file().getId(),
                                "assignments",
                                List.of(new ReviewAssignmentId(
                                        reviewId,
                                        scenario.owner().practice().getId(),
                                        scenario.file().getId())))))
                .param("trace", id.toString().replace("-", ""))
                .param("at", Timestamp.from(Instant.now()))
                .update();
        return id;
    }

    @Test
    void shouldFreezeCancellationCountsOnlyAfterTerminalProcessingRecordsTheFailure() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        long workspaceId = scan.getWorkspaceId();
        UUID reviewId = scan.getId();
        var jobId = invocation(workspaceId, reviewId, ReviewJobStatus.RUNNING);
        var assignment = new ReviewAssignmentId(
                reviewId, scenario.owner().practice().getId(), scenario.file().getId());
        assignments.bind(workspaceId, reviewId, jobId, List.of(assignment));
        jdbc.sql("UPDATE review_job SET"
                        + " status='CANCELLED',started_at=now(),execution_started_at=now(),completed_at=now()"
                        + " WHERE workspace_id=:ws AND id=:id")
                .param("ws", workspaceId)
                .param("id", jobId)
                .update();
        when(runtime.activeJobIds(workspaceId, reviewId)).thenReturn(Set.of());
        when(runtime.unsettledJobIds(workspaceId, reviewId)).thenReturn(Set.of(jobId));
        assertThat(reviews.cancel(workspaceId, reviewId, "CANCELLED_BY_ADMIN")).isTrue();
        assertThat(jdbc.sql("SELECT report IS NULL FROM review WHERE workspace_id=:ws AND id=:id")
                        .param("ws", workspaceId)
                        .param("id", reviewId)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assignments.fail(
                workspaceId,
                assignment,
                ReviewStopReason.CANCELLED,
                "The invoked execution stopped before admitting a result.");
        jdbc.sql("UPDATE review_job SET processing_status='FAILED' WHERE workspace_id=:ws AND id=:id")
                .param("ws", workspaceId)
                .param("id", jobId)
                .update();
        when(runtime.unsettledJobIds(workspaceId, reviewId)).thenReturn(Set.of());
        reviews.complete(
                workspaceId,
                reviewId,
                new ReviewCompletion(
                        ReviewStatus.CANCELLED, ReviewStopReason.CANCELLED, Instant.now(), scan.getOrderedAt()));
        var report = query.report(workspaceId, reviewId);
        assertThat(report.scan().status()).isEqualTo(ReviewStatus.CANCELLED);
        var recorded = report.recordedCounts();
        assertThat(recorded).isNotNull();
        assertThat(recorded.withheld()).isOne();
        assertThat(recorded.notReviewed()).isZero();
        assertThat(recorded.reviewed()).isZero();
        var evidence = overview.evidenceOutcomes(workspaceId).getFirst();
        assertThat(evidence.withheld()).isOne();
        assertThat(evidence.notReviewed()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM result WHERE workspace_id=:ws AND review_id=:review")
                        .param("ws", workspaceId)
                        .param("review", reviewId)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void shouldRetainFileCaptureRefusalWithoutWithholdingTheWholeScanPractice() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        var other = Objects.requireNonNull(new TransactionTemplate(transactions).execute(tx -> {
            var created = new Item();
            created.setWorkspaceId(scan.getWorkspaceId());
            created.setKind(new ItemKind("scm.file"));
            created.setParentId(scenario.repository().getId());
            created.setPath("src/Other.java");
            created.setCreatedAt(Instant.now());
            items.saveAndFlush(created);
            membership.saveAndFlush(ReviewItem.builder()
                    .workspaceId(scan.getWorkspaceId())
                    .reviewId(scan.getId())
                    .practiceId(scenario.owner().practice().getId())
                    .itemId(created.getId())
                    .itemVersion("file-version-two")
                    .build());
            jdbc.sql("UPDATE review_practice SET items_listed=2 WHERE workspace_id=:ws AND review_id=:review AND"
                            + " practice_id=:practice")
                    .param("ws", scan.getWorkspaceId())
                    .param("review", scan.getId())
                    .param("practice", scenario.owner().practice().getId())
                    .update();
            return created;
        }));
        var job = invocation(scan.getWorkspaceId(), scan.getId(), ReviewJobStatus.RUNNING);
        assignments.bind(
                scan.getWorkspaceId(),
                scan.getId(),
                job,
                List.of(new ReviewAssignmentId(
                        scan.getId(),
                        scenario.owner().practice().getId(),
                        scenario.file().getId())));
        when(runtime.runtime(scan.getWorkspaceId(), scan.getId()))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        job,
                        ReviewJobStatus.RUNNING,
                        0,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        job.toString())))));
        when(runtime.activeJobIds(scan.getWorkspaceId(), scan.getId())).thenReturn(Set.of(job));
        var copiedPerson = new PersonCopyIdentity("GITHUB", "https://github.com", "copied-from-refused-file", null);
        when(custody.find(scan.getWorkspaceId(), List.of(job)))
                .thenReturn(new HistoryCustody(false, false, List.of(copiedPerson), List.of(), List.of(job)));
        assignments.capture(
                scan.getWorkspaceId(),
                scan.getId(),
                job,
                0,
                List.of(new ReviewCaptureDecision(
                        scenario.owner().practice().getId(),
                        ReviewPracticeStatus.FAILED,
                        "CAPTURE_FAILED",
                        json.createObjectNode().put("status", "FAILED"))));
        var refused = membership
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        scan.getWorkspaceId(),
                        scan.getId(),
                        scenario.owner().practice().getId(),
                        scenario.file().getId())
                .orElseThrow();
        var detail = refused.getDetail();
        assertThat(detail).isNotNull();
        assertThat(detail.path("captureDecision").path("status").asString()).isEqualTo("FAILED");
        // One refused file leaves the practice running for the scan's other files.
        assertThat(jdbc.sql("SELECT status FROM review_practice WHERE workspace_id=:ws AND review_id=:review AND"
                                + " practice_id=:practice")
                        .param("ws", scan.getWorkspaceId())
                        .param("review", scan.getId())
                        .param("practice", scenario.owner().practice().getId())
                        .query(String.class)
                        .single())
                .isEqualTo("RUNNING");
        assertThat(detail.path("custody").path("complete").asBoolean()).isFalse();
        assertThat(detail.path("custody").path("sourceJobIds").get(0).asString())
                .isEqualTo(job.toString());
        assertThat(refused.getResultId()).isNull();
        assertThat(store.pending(scan.getWorkspaceId(), scan.getId(), 10))
                .extracting(s -> s.id().itemId())
                .containsExactly(other.getId());
        when(people.providers())
                .thenReturn(List.of(new PersonIdentityResolver.Provider(8, "GITHUB", "https://github.com")));
        jdbc.sql("UPDATE review_item SET job_id=NULL WHERE workspace_id=:ws AND job_id=:job")
                .param("ws", scan.getWorkspaceId())
                .param("job", job)
                .update();
        jdbc.sql("DELETE FROM review_job WHERE workspace_id=:ws AND id=:job")
                .param("ws", scan.getWorkspaceId())
                .param("job", job)
                .update();
        var capturedStore = personData.contributors().stream()
                .filter(c -> c.store().equals("review_item"))
                .findFirst()
                .orElseThrow();
        var capturedRows = capturedStore
                .select(new PersonScope(
                        null, List.of(new PersonIdentity(8, "copied-from-refused-file", null)), List.of()))
                .rows();
        assertThat(capturedRows)
                .anySatisfy(key -> assertThat(key.columns().get("item_id"))
                        .isEqualTo(Long.toString(scenario.file().getId())));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var snapshot = store.lock(scan.getWorkspaceId(), scan.getId());
            assertThat(snapshot.review().practices()).hasSize(1);
            assertThat(snapshot.listed()).isEqualTo(2);
            assertThat(snapshot.withheld()).isOne();
        });
    }

    @Test
    void acceptedChangesOpenCountsAndPagesWithoutChangingRecordedFacts() {
        var scan = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(60));
        var result = data.result(scenario, scan);
        var before = query.report(scan.getWorkspaceId(), scan.getId());
        marks.create(
                scan.getWorkspaceId(),
                result.getId(),
                ResultMarkKind.ACCEPTED,
                scenarioAccountId,
                "This tradeoff is accepted");
        var after = query.report(scan.getWorkspaceId(), scan.getId());
        assertThat(after.counts().openProblems()).isZero();
        assertThat(after.recordedCounts()).isEqualTo(before.recordedCounts());
        assertThat(after.problemChanges()).isEqualTo(before.problemChanges());
        assertThat(after.practices().getFirst().counts().openProblems()).isZero();
        assertThat(reportItems
                        .problems(
                                scan.getWorkspaceId(),
                                scan.getId(),
                                null,
                                20,
                                new ScanMembershipQueries.ProblemFilter(null, null, null, null, true))
                        .content())
                .isEmpty();
        assertThat(reportItems
                        .problems(
                                scan.getWorkspaceId(),
                                scan.getId(),
                                null,
                                20,
                                new ScanMembershipQueries.ProblemFilter(null, null, null, null, false))
                        .content())
                .hasSize(2);
    }

    @Test
    void shouldReportAnExcludedFileWithoutWithholdingOrInventingAnOutcome() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        var practice = scenario.owner().practice().getId();
        var file = scenario.file();
        membership.deleteById(new ReviewItem.Id(scan.getId(), practice, file.getId()));
        jdbc.sql("UPDATE review_practice SET items_listed=0 WHERE workspace_id=:ws AND review_id=:review")
                .param("ws", scan.getWorkspaceId())
                .param("review", scan.getId())
                .update();
        store.exclusions(
                scan.getWorkspaceId(),
                scan.getId(),
                List.of(new ScanExcludedItem(
                        new ItemRef(
                                file.getWorkspaceId(),
                                file.getId(),
                                file.getKind(),
                                null,
                                file.getParentId(),
                                file.getPath()),
                        ItemExclusionReason.FILE_TOO_LARGE,
                        2_097_153)));
        data.terminal(scan, ReviewStatus.COMPLETE, null);
        var report = query.report(scan.getWorkspaceId(), scan.getId());
        assertThat(report.complete()).isTrue();
        assertThat(report.counts().files()).isZero();
        assertThat(report.counts().practiceItemPairs()).isZero();
        assertThat(report.counts().met()).isZero();
        assertThat(report.counts().withheld()).isZero();
        var excluded = report.exclusions();
        assertThat(excluded).isNotNull().hasSize(1);
        assertThat(excluded.getFirst().reason()).isEqualTo(ItemExclusionReason.FILE_TOO_LARGE);
        assertThat(excluded.getFirst().path()).isEqualTo(file.getPath());
        assertThat(excluded.getFirst().sizeBytes()).isEqualTo(2_097_153);
    }

    @Test
    void shouldPrioritizeUnreviewedThenOldestFilesBeforePathOrder() {
        var oldFile = items.findById(scenario.file().getId()).orElseThrow();
        var oldScenario = new ScanReportTestData.Scenario(scenario.owner(), scenario.repository(), oldFile);
        var at = Instant.now().minusSeconds(300);
        data.result(oldScenario, data.scan(oldScenario, ReviewStatus.COMPLETE, at));
        var recentFile = new Item();
        recentFile.setWorkspaceId(oldFile.getWorkspaceId());
        recentFile.setKind(oldFile.getKind());
        recentFile.setParentId(oldFile.getParentId());
        recentFile.setPath("a-recent.java");
        recentFile.setCreatedAt(at);
        items.saveAndFlush(recentFile);
        var recentScenario = new ScanReportTestData.Scenario(scenario.owner(), scenario.repository(), recentFile);
        data.result(recentScenario, data.scan(recentScenario, ReviewStatus.COMPLETE, at.plusSeconds(100)));
        var unseenFile = new Item();
        unseenFile.setWorkspaceId(oldFile.getWorkspaceId());
        unseenFile.setKind(oldFile.getKind());
        unseenFile.setParentId(oldFile.getParentId());
        unseenFile.setPath("zz-unseen.java");
        unseenFile.setCreatedAt(at);
        items.saveAndFlush(unseenFile);
        var current = data.scan(oldScenario, ReviewStatus.RUNNING, at.plusSeconds(200));
        var practice = scenario.owner().practice().getId();
        for (var file : List.of(recentFile, unseenFile)) {
            membership.saveAndFlush(ReviewItem.builder()
                    .workspaceId(current.getWorkspaceId())
                    .reviewId(current.getId())
                    .practiceId(practice)
                    .itemId(file.getId())
                    .itemVersion(ScanReportTestData.version())
                    .build());
        }
        assertThat(membership.pending(current.getWorkspaceId(), current.getId(), new UUID[0], PageRequest.of(0, 10)))
                .extracting(ReviewItemRepository.PendingAssignment::getItemId)
                .containsExactly(unseenFile.getId(), oldFile.getId(), recentFile.getId());
        assertThat(membership.pending(
                        current.getWorkspaceId() + 1, current.getId(), new UUID[0], PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void reusedMembershipKeepsItsOriginalProducerAndAcceptedVerdictIsReusable() {
        var original = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(120));
        var result = data.result(scenario, original);
        marks.create(
                original.getWorkspaceId(),
                result.getId(),
                ResultMarkKind.ACCEPTED,
                scenarioAccountId,
                "Accept the known tradeoff");
        var current = data.scan(scenario, ReviewStatus.RUNNING, Instant.now().minusSeconds(10));
        var id = new ReviewAssignmentId(current.getId(), result.getPracticeId(), result.getItemId());
        assertThat(store.reuse(current.getWorkspaceId(), id)).contains(result.getId());
        data.terminal(current, ReviewStatus.COMPLETE, null);
        var page = reportItems.items(
                current.getWorkspaceId(),
                current.getId(),
                null,
                10,
                new ScanMembershipQueries.ItemFilter(null, null, null, null, null));
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst()).isInstanceOf(ReusedScanReportItemDTO.class);
        var report = query.report(current.getWorkspaceId(), current.getId());
        assertThat(report.counts().reviewed()).isOne();
        assertThat(report.counts().reused()).isOne();
        assertThat(report.counts().openProblems()).isZero();
    }

    @Test
    void shouldPersistOneReusePassAndKeepReusedMembershipAfterALaterMark() {
        var original = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(120));
        var result = data.result(scenario, original);
        var current = data.scan(scenario, ReviewStatus.RUNNING, Instant.now().minusSeconds(10));
        reuse.reuseListed(
                current.getWorkspaceId(), current.getId(), Instant.now().plusSeconds(60));
        var member = membership
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        current.getWorkspaceId(), current.getId(), result.getPracticeId(), result.getItemId())
                .orElseThrow();
        assertThat(member.getState()).isEqualTo(ReviewItemState.REUSED);
        assertThat(member.getResultId()).isEqualTo(result.getId());
        assertThat(reports.require(current.getWorkspaceId(), current.getId())
                        .getChecks()
                        .path("reuseComplete")
                        .asBoolean())
                .isTrue();
        marks.create(
                current.getWorkspaceId(),
                result.getId(),
                ResultMarkKind.INCORRECT,
                scenarioAccountId,
                "The claim is incorrect");
        reuse.reuseListed(
                current.getWorkspaceId(), current.getId(), Instant.now().plusSeconds(60));
        var id = new ReviewAssignmentId(current.getId(), result.getPracticeId(), result.getItemId());
        assertThat(store.reuse(current.getWorkspaceId(), id)).contains(result.getId());
    }

    @Test
    void runtimeWithheldAndClosedUnreachedRemainSeparate() {
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        assignments.withhold(
                scan.getWorkspaceId(),
                new ReviewAssignmentId(
                        scan.getId(),
                        scenario.owner().practice().getId(),
                        scenario.file().getId()),
                CandidateRefusal.QUOTE_UNVERIFIED,
                "The captured text cannot verify this quote.");
        data.terminal(scan, ReviewStatus.PARTIAL, ReviewStopReason.RESULT_WITHHELD);
        assertThat(query.report(scan.getWorkspaceId(), scan.getId()).counts().withheld())
                .isOne();
        var next = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        data.terminal(next, ReviewStatus.CANCELLED, ReviewStopReason.CANCELLED);
        var report = query.report(next.getWorkspaceId(), next.getId());
        assertThat(report.counts().withheld()).isZero();
        assertThat(report.counts().notReviewed()).isOne();
    }

    @Test
    void inaccessibleProofIsRedactedAndReportedUnavailable() {
        var scan = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(60));
        data.result(scenario, scan);
        when(authorization.permitsAll(anyLong(), anyCollection(), any())).thenReturn(Set.of());
        var report = query.report(scan.getWorkspaceId(), scan.getId());
        assertThat(report.availability().withheldProofPairs()).isOne();
        assertThat(report.counts().problems()).isEqualTo(2);
        assertThat(report.counts().openProblems()).isZero();
        assertThat(reportItems
                        .problems(
                                scan.getWorkspaceId(),
                                scan.getId(),
                                null,
                                20,
                                new ScanMembershipQueries.ProblemFilter(null, null, null, null, false))
                        .content())
                .isEmpty();
    }

    @Test
    void rawExpiryKeepsAggregateAndReturnsGoneForProofPages() {
        var scan = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(401L * 86400));
        data.result(scenario, scan);
        retention.purgeWorkspace(scan.getWorkspaceId(), Instant.now().minusSeconds(400L * 86400));
        var report = query.report(scan.getWorkspaceId(), scan.getId());
        assertThat(report.availability().expired()).isTrue();
        var recorded = report.recordedCounts();
        assertThat(recorded).isNotNull();
        assertThat(recorded.problems()).isEqualTo(2);
        assertThatThrownBy(() -> reportItems.items(
                        scan.getWorkspaceId(),
                        scan.getId(),
                        null,
                        20,
                        new ScanMembershipQueries.ItemFilter(null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error)
                                .getStatusCode()
                                .value())
                        .isEqualTo(410));
    }

    @Test
    void problemCursorCannotBeReplayedAgainstAnotherScan() {
        var first = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(60));
        data.result(scenario, first);
        var page = reportItems.problems(
                first.getWorkspaceId(),
                first.getId(),
                null,
                1,
                new ScanMembershipQueries.ProblemFilter(null, null, null, null, false));
        assertThat(page.hasNext()).isTrue();
        var second = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now());
        assertThatThrownBy(() -> reportItems.problems(
                        second.getWorkspaceId(),
                        second.getId(),
                        page.nextCursor(),
                        1,
                        new ScanMembershipQueries.ProblemFilter(null, null, null, null, false)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void erasureRemovesProofReferencesAndKeepsLabelledCloseCounts() {
        var scan = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(60));
        var result = data.result(scenario, scan);
        var before = query.report(scan.getWorkspaceId(), scan.getId());
        assertThat(reports.eraseResultReferences(scan.getWorkspaceId(), List.of(result.getId())))
                .isOne();
        var after = query.report(scan.getWorkspaceId(), scan.getId());
        assertThat(after.recordedCounts()).isEqualTo(before.recordedCounts());
        assertThat(after.availability().withheldProofPairs()).isOne();
        assertThat(reportItems
                        .items(
                                scan.getWorkspaceId(),
                                scan.getId(),
                                null,
                                20,
                                new ScanMembershipQueries.ItemFilter(null, null, null, null, null))
                        .content())
                .isEmpty();
    }

    @Test
    void exactlyTwentyPercentRemovalIsSuspectAndIncompleteListingNeverRemoves() {
        var present = new ArrayList<Long>();
        present.add(scenario.file().getId());
        for (int n = 0; n < 4; n++) {
            var file = new Item();
            file.setWorkspaceId(scenario.owner().workspace().getId());
            file.setKind(new ItemKind("scm.file"));
            file.setParentId(scenario.repository().getId());
            file.setPath("src/Other" + n + ".java");
            file.setCreatedAt(Instant.now().minusSeconds(7200));
            items.saveAndFlush(file);
            present.add(file.getId());
        }
        var scan = data.scan(scenario, ReviewStatus.RUNNING, Instant.now());
        store.inventory(scan.getWorkspaceId(), scan.getId(), present.subList(0, 4), true);
        var guard = query.report(scan.getWorkspaceId(), scan.getId()).removalGuard();
        assertThat(guard.previousFiles()).isEqualTo(5);
        assertThat(guard.wouldRemove()).isOne();
        assertThat(guard.state()).isEqualTo(RemovalGuardState.SUSPECT_LISTING);
        assertThat(items.findById(present.getLast()).orElseThrow().getRemovedAt())
                .isNull();
        store.inventory(scan.getWorkspaceId(), scan.getId(), List.of(), false);
        assertThat(query.report(scan.getWorkspaceId(), scan.getId())
                        .removalGuard()
                        .state())
                .isEqualTo(RemovalGuardState.INCOMPLETE_LISTING);
        assertThat(items.findById(scenario.file().getId()).orElseThrow().getRemovedAt())
                .isNull();
    }

    @Test
    void retainedCustodySelectsDeveloperlessCopiedProofWithoutRuntimeJobRows() {
        var scan = data.scan(scenario, ReviewStatus.COMPLETE, Instant.now().minusSeconds(60));
        var result = data.result(scenario, scan);
        assertThat(result.getDeveloperId()).isNull();
        var member = membership
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        scan.getWorkspaceId(), scan.getId(), result.getPracticeId(), result.getItemId())
                .orElseThrow();
        var copy = new PersonCopyIdentity("GITHUB", "https://github.com", "copied-person", null);
        var detail = (ObjectNode) Objects.requireNonNull(member.getDetail()).deepCopy();
        detail.set(
                "custody",
                json.valueToTree(new HistoryCustody(
                        true, false, List.of(copy), List.of(), List.of(Objects.requireNonNull(member.getJobId())))));
        member.setDetail(detail);
        membership.saveAndFlush(member);
        when(people.providers())
                .thenReturn(List.of(new PersonIdentityResolver.Provider(8, "GITHUB", "https://github.com")));
        var person = new PersonScope(null, List.of(new PersonIdentity(8, "copied-person", null)), List.of());
        var store = personData.contributors().stream()
                .filter(c -> c.store().equals("result"))
                .findFirst()
                .orElseThrow();
        assertThat(store.select(person).rows())
                .anySatisfy(key -> assertThat(key.columns().get("id"))
                        .isEqualTo(result.getId().toString()));
        assertThat(store.select(new PersonScope(
                                null, List.of(new PersonIdentity(8, "different-person", null)), List.of()))
                        .rows())
                .isEmpty();
    }
}
