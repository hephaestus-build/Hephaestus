package de.tum.cit.aet.hephaestus.agent.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCancellationReason;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobStateConflictException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeQueryAdapter;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueue;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanReuse;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScanStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFact;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFacts;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFactsQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ScanExecutionSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ScanReviewIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ReviewQueue runtimeQueue;

    @Autowired
    private ReviewRuntimeRepository runtimeRows;

    @Autowired
    private ReviewService reviews;

    private ReviewJob job(long workspace, UUID review) {
        var job = new ReviewJob();
        job.setReviewId(review);
        RuntimeJobTestData.initialize(job, workspace);
        job.setConfigSnapshot(new JsonMapper().createObjectNode());
        job.prePersist();
        return job;
    }

    @Test
    void serialFileJobsUseTheSameQueueAndWaitForTerminalProcessing() {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("serial-scan-runtime"));
        var first = RuntimeJobTestData.persistParent(jdbc, job(workspace.getId(), UUID.randomUUID()));
        jdbc.update(
                "UPDATE review SET kind='SCAN',budget_usd=10 WHERE id=? AND workspace_id=?",
                first.getReviewId(),
                workspace.getId());
        jobs.saveAndFlush(first);
        var second = job(workspace.getId(), first.getReviewId());
        second.setInput(first.getInput());
        var root = new ItemRef(workspace.getId(), first.getInput().itemId(), new ItemKind("test.item"), 1L, null, null);
        var review = new ReviewSnapshot(
                first.getReviewId(),
                workspace.getId(),
                root,
                ReviewKind.SCAN,
                new ItemVersion("test:1", null),
                ReviewOrigin.REQUEST,
                0,
                List.of());
        assertThat(runtimeQueue.enqueue(
                        review, first.getInput().itemId(), first.getInput().assignments()))
                .isEqualTo(first.getId());
        long otherFile = first.getInput().itemId() + 1;
        assertThatThrownBy(() -> runtimeQueue.enqueue(
                        review, otherFile, List.of(new ReviewAssignmentId(first.getReviewId(), 1, otherFile))))
                .isInstanceOf(ReviewJobStateConflictException.class);
        assertThat(jobs.findByReviewIdAndWorkspaceIdAndStatusIn(
                        first.getReviewId(),
                        workspace.getId(),
                        Set.of(ReviewJobStatus.QUEUED, ReviewJobStatus.RUNNING)))
                .isPresent();
        transactions.executeWithoutResult(status -> {
            var stored = jobs.findByIdAndWorkspaceIdForUpdate(first.getId(), workspace.getId())
                    .orElseThrow();
            stored.setStatus(ReviewJobStatus.COMPLETED);
            stored.setCompletedAt(Instant.now());
            stored.setProcessingStatus(ProcessingStatus.PENDING);
            jobs.saveAndFlush(stored);
        });
        assertThat(jobs.hasUnsettledInvocation(workspace.getId(), first.getReviewId()))
                .isTrue();
        var candidate = jobs.findTerminalForProcessing(100).stream()
                .filter(row -> row.getId().equals(first.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(candidate.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(candidate.getReviewId()).isEqualTo(first.getReviewId());
        assertThat(jobs.hasUnsettledInvocation(workspace.getId() + 1000, first.getReviewId()))
                .isFalse();
        transactions.executeWithoutResult(status -> {
            var stored = jobs.findByIdAndWorkspaceIdForUpdate(first.getId(), workspace.getId())
                    .orElseThrow();
            stored.setProcessingStatus(ProcessingStatus.DONE);
            jobs.saveAndFlush(stored);
        });
        assertThat(jobs.hasUnsettledInvocation(workspace.getId(), first.getReviewId()))
                .isFalse();
        var saved = jobs.saveAndFlush(second);
        assertThat(saved.getReviewId()).isEqualTo(first.getReviewId());
        assertThat(jobs.findByReviewIdAndWorkspaceIdOrderByAvailableAtAsc(first.getReviewId(), workspace.getId()))
                .hasSize(2);
        assertThat(Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT kind FROM review WHERE id=? AND workspace_id=?",
                        String.class,
                        first.getReviewId(),
                        workspace.getId())))
                .isEqualTo("SCAN");
    }

    @Test
    void shouldEndAScanForCapacityInsteadOfQueueingItsNextFileOnceCapacityCancelledAJob() {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("scan-no-sandbox-room"));
        var cancelled = job(workspace.getId(), UUID.randomUUID());
        cancelled.setStatus(ReviewJobStatus.CANCELLED);
        cancelled.setCancellationReason(ReviewJobCancellationReason.NO_SANDBOX_ROOM);
        cancelled.setCompletedAt(Instant.now());
        cancelled.setProcessingStatus(ProcessingStatus.FAILED);
        RuntimeJobTestData.persistParent(jdbc, cancelled);
        jobs.saveAndFlush(cancelled);
        jdbc.update(
                "UPDATE review SET kind='SCAN',budget_usd=10,status='RUNNING' WHERE id=? AND workspace_id=?",
                cancelled.getReviewId(),
                workspace.getId());
        var file = RuntimeReviewTestData.assignment(null, null);
        var review = new ReviewSnapshot(
                cancelled.getReviewId(),
                workspace.getId(),
                new ItemRef(
                        workspace.getId(), cancelled.getInput().itemId(), new ItemKind("test.item"), 1L, null, null),
                ReviewKind.SCAN,
                new ItemVersion("test:1", null),
                ReviewOrigin.REQUEST,
                0,
                List.of(file.practice()));
        var scans = mock(ReviewScanStore.class);
        when(scans.pending(anyLong(), any(), anyInt())).thenReturn(List.of(file));
        var budget = mock(ScanBudget.class);
        when(budget.permits(any(), any())).thenReturn(true);
        var queue = mock(ReviewQueue.class);
        var driver = new ScanDriver(
                scans,
                mock(ReviewScanReuse.class),
                reviews,
                mock(ScanService.class),
                budget,
                queue,
                jobs,
                mock(ReviewJobLifecycleService.class),
                transactions,
                new ScanProperties(100, 10000, Duration.ofMinutes(1), Duration.ofHours(24)),
                Clock.systemUTC());

        transactions.executeWithoutResult(status -> driver.advance(new ScanExecutionSnapshot(
                review,
                ReviewStatus.RUNNING,
                BigDecimal.TEN,
                Instant.now(),
                false,
                true,
                true,
                Set.of(file.practice().practiceId()),
                2,
                0,
                0,
                1)));

        verify(queue, never()).enqueue(any(), anyLong(), any());
        assertThat(jdbc.queryForMap(
                        "SELECT status, stop_reason FROM review WHERE id=? AND workspace_id=?",
                        cancelled.getReviewId(),
                        workspace.getId()))
                .containsEntry("status", "CANCELLED")
                .containsEntry("stop_reason", "NO_SANDBOX_ROOM");
    }

    @Test
    void shouldExcludeMigratedHistoryBeforeLimitingCompletionCandidatesAcrossWorkspaces() {
        var firstWorkspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("completion-native-first"));
        var secondWorkspace =
                workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("completion-native-second"));
        var historyAt = Instant.parse("2000-01-01T00:00:00Z");
        var modernAt = historyAt.plusSeconds(60);
        var historical = job(firstWorkspace.getId(), UUID.randomUUID());
        historical.setId(historical.getReviewId());
        historical.setStatus(ReviewJobStatus.COMPLETED);
        historical.setCompletedAt(historyAt);
        historical.setProcessingStatus(ProcessingStatus.NONE);
        RuntimeJobTestData.persistParent(jdbc, historical);
        jobs.saveAndFlush(historical);
        jdbc.update(
                "UPDATE review SET checks=CAST(? AS jsonb) WHERE id=? AND workspace_id=?",
                "{\"MIGRATION\":{\"jobStatus\":\"COMPLETED\",\"processingStatus\":\"NONE\"}}",
                historical.getReviewId(),
                firstWorkspace.getId());

        var nativeNone = job(firstWorkspace.getId(), historical.getReviewId());
        nativeNone.setInput(historical.getInput());
        nativeNone.setStatus(ReviewJobStatus.FAILED);
        nativeNone.setCompletedAt(modernAt);
        RuntimeJobTestData.persistParent(jdbc, nativeNone);
        jobs.saveAndFlush(nativeNone);
        var nativePending = job(secondWorkspace.getId(), UUID.randomUUID());
        nativePending.setStatus(ReviewJobStatus.COMPLETED);
        nativePending.setCompletedAt(modernAt.plusSeconds(1));
        nativePending.setProcessingStatus(ProcessingStatus.PENDING);
        RuntimeJobTestData.persistParent(jdbc, nativePending);
        jobs.saveAndFlush(nativePending);

        var candidates = jobs.findTerminalForProcessing(2);
        assertThat(candidates)
                .extracting(ReviewJobRepository.CompletionCandidate::getId)
                .containsExactly(nativeNone.getId(), nativePending.getId());
        assertThat(candidates)
                .extracting(ReviewJobRepository.CompletionCandidate::getWorkspaceId)
                .containsExactly(firstWorkspace.getId(), secondWorkspace.getId());
        assertThat(jobs.findByIdAndWorkspaceId(historical.getId(), firstWorkspace.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getProcessingStatus)
                .containsExactly(ReviewJobStatus.COMPLETED, ProcessingStatus.NONE);
        assertThat(jobs.findByIdAndWorkspaceId(nativePending.getId(), firstWorkspace.getId()))
                .isEmpty();
    }

    @Test
    void timingUsesPinnedProductCohortsAndSeparatesPublicFromPrivateCompletion() {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("runtime-timing-cohort"));
        var at = Instant.parse("2026-10-14T10:00:00Z");
        var first = job(workspace.getId(), UUID.randomUUID());
        first.setAvailableAt(at);
        first.setStartedAt(at.plusSeconds(10));
        first.setExecutionStartedAt(at.plusSeconds(20));
        first.setCompletedAt(at.plusSeconds(100));
        first.setStatus(ReviewJobStatus.COMPLETED);
        first.setProcessingStatus(ProcessingStatus.DONE);
        RuntimeJobTestData.persistParent(jdbc, first);
        jobs.saveAndFlush(first);
        var held = job(workspace.getId(), first.getReviewId());
        held.setInput(first.getInput());
        held.setAvailableAt(at.minusSeconds(20));
        held.setHoldReason(ReviewJob.HOLD_REASON_BUDGET);
        jobs.saveAndFlush(held);
        var retained = UUID.randomUUID();
        var facts = mock(ReviewTimingFactsQuery.class);
        var from = at.minusSeconds(10);
        var to = at.plusSeconds(1000);
        when(facts.window(workspace.getId(), from, to))
                .thenReturn(List.of(
                        new ReviewTimingFact(
                                first.getReviewId(),
                                ReviewOrigin.REQUEST,
                                at,
                                null,
                                at.plusSeconds(100),
                                at.plusSeconds(30),
                                at.plusSeconds(40),
                                at.plusSeconds(90),
                                null,
                                true,
                                false),
                        new ReviewTimingFact(
                                retained,
                                ReviewOrigin.REQUEST,
                                at,
                                null,
                                at.plusSeconds(60),
                                at.plusSeconds(20),
                                at.plusSeconds(30),
                                null,
                                null,
                                true,
                                false)));
        when(facts.facts(workspace.getId(), List.of(first.getReviewId(), retained), from, to))
                .thenReturn(new ReviewTimingFacts(0, 0, 0, 0, 0));
        var adapter = new ReviewRuntimeQueryAdapter(jobs, mock(ReviewAssignmentQuery.class), runtimeRows, facts);
        var timing = adapter.timing(workspace.getId(), from, to, at.plusSeconds(10));
        assertThat(Objects.requireNonNull(timing.stages().get("ASKED")).count()).isEqualTo(2);
        assertThat(timing.waitingLong()).isZero();
        var queue = adapter.queue(workspace.getId(), at.plusSeconds(1000), 10);
        assertThat(queue.waiting()).isZero();
        assertThat(queue.heldForBudget()).isEqualTo(1);
        assertThat(queue.oldestWaitingSince()).isNull();
        assertThat(Objects.requireNonNull(timing.stages().get("QUEUE")).medianSeconds())
                .isEqualTo(10d);
        assertThat(Objects.requireNonNull(timing.stages().get("POSTED")).count())
                .isEqualTo(2);
        assertThat(Objects.requireNonNull(timing.stages().get("TOTAL")).medianSeconds())
                .isEqualTo(35d);
        assertThat(Objects.requireNonNull(timing.stages().get("TOTAL")).p90Seconds())
                .isEqualTo(39d);
        assertThat(Objects.requireNonNull(timing.total()).model()).isEqualTo(10d);
        assertThat(Objects.requireNonNull(timing.finished()).model()).isEqualTo(80d);
        assertThat(Objects.requireNonNull(timing.stages().get("READ")).medianSeconds())
                .isEqualTo(60d);
    }
}
