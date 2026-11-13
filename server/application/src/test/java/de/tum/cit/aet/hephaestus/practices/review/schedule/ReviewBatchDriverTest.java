package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchPauseReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;

class ReviewBatchDriverTest extends BaseUnitTest {
    final ReviewBatchRepository batches = mock(ReviewBatchRepository.class);
    final ReviewBatchScopeQuery scope = mock(ReviewBatchScopeQuery.class);
    final ReviewBatchSubmitter submitter = mock(ReviewBatchSubmitter.class);
    final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    final ReviewBudget budget = mock(ReviewBudget.class);
    final ReviewBatchSpend spend = mock(ReviewBatchSpend.class);
    final ReviewRepository reviews = mock(ReviewRepository.class);
    final PracticeReviewReadiness readiness = mock(PracticeReviewReadiness.class);
    final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    final ReviewBatchDriver driver = new ReviewBatchDriver(
            batches,
            scope,
            submitter,
            workspaces,
            budget,
            readiness,
            new ReviewBatchProperties(2, Duration.ofDays(400), 100, Duration.ofDays(90)),
            Clock.fixed(now, ZoneOffset.UTC),
            reviews,
            spend);
    ReviewBatch batch;
    Workspace workspace;

    @BeforeEach
    void setUp() {
        batch = ReviewBatch.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .kind("scm.pull_request")
                .origin(ReviewBatchOrigin.BACKFILL)
                .status(ReviewBatchStatus.RUNNING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        workspace = new Workspace();
        workspace.setId(1L);
        workspace.resume();
        lenient().when(spend.remaining(batch)).thenReturn(new ReviewBatchSpend.Snapshot(null, true));
        lenient().when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
        lenient().when(readiness.hasRunnableAgent(1L)).thenReturn(true);
        lenient().when(budget.canRequest(1, ReviewKind.ITEM, null)).thenReturn(true);
        lenient().when(scope.next(1, batch.getId(), null, 2)).thenReturn(List.of(1L));
    }

    @Test
    void shouldEndAScheduledRunWhenItsFixedCapIsSpent() {
        batch.setOrigin(ReviewBatchOrigin.SCHEDULE);
        batch.setKind("scm.repository");
        batch.setBudgetUsd(BigDecimal.ONE);
        when(spend.remaining(batch)).thenReturn(new ReviewBatchSpend.Snapshot(BigDecimal.ZERO, true));
        when(scope.next(1, batch.getId(), null, 1)).thenReturn(List.of(6L));

        driver.advance(batch);

        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.BUDGET_EXHAUSTED);
        assertThat(batch.getPauseReason()).isNull();
        assertThat(batch.getFinishedAt()).isEqualTo(now);
        assertThat(batch.getCursorItemId()).isNull();
        verifyNoInteractions(submitter);
    }

    @Test
    void shouldEndAnExistingScheduledBudgetPauseOnTheNextTick() {
        batch.setOrigin(ReviewBatchOrigin.SCHEDULE);
        batch.setKind("scm.repository");
        when(scope.next(1, batch.getId(), null, 1)).thenReturn(List.of(6L));
        batch.setStatus(ReviewBatchStatus.PAUSED);
        batch.setPauseReason(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
        batch.setBudgetUsd(BigDecimal.ONE);
        when(spend.remaining(batch)).thenReturn(new ReviewBatchSpend.Snapshot(BigDecimal.ZERO, true));

        driver.advance(batch);

        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.BUDGET_EXHAUSTED);
        assertThat(batch.getPauseReason()).isNull();
        assertThat(batch.getFinishedAt()).isEqualTo(now);
    }

    @ParameterizedTest
    @EnumSource(ReviewBatchOrigin.class)
    void shouldCompleteAnEmptyScopeEvenWhenNoBudgetRemains(ReviewBatchOrigin origin) {
        batch.setOrigin(origin);
        batch.setStatus(ReviewBatchStatus.PAUSED);
        batch.setPauseReason(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
        when(scope.next(1, batch.getId(), null, 2)).thenReturn(List.of());
        lenient().when(budget.canRequest(1, ReviewKind.ITEM, null)).thenReturn(false);

        driver.advance(batch);

        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.COMPLETED);
        assertThat(batch.getPauseReason()).isNull();
        assertThat(batch.getFinishedAt()).isEqualTo(now);
        verifyNoInteractions(spend, budget, submitter);
    }

    @Test
    void shouldKeepUnpricedScheduledUsagePausedUntilItsCostIsKnown() {
        batch.setOrigin(ReviewBatchOrigin.SCHEDULE);
        batch.setKind("scm.repository");
        batch.setBudgetUsd(BigDecimal.ONE);
        when(scope.next(1, batch.getId(), null, 1)).thenReturn(List.of(6L));
        when(spend.remaining(batch)).thenReturn(new ReviewBatchSpend.Snapshot(null, false));

        driver.advance(batch);

        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.PAUSED);
        assertThat(batch.getPauseReason()).isEqualTo(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
        assertThat(batch.getFinishedAt()).isNull();
    }

    @Test
    void shouldKeepAScheduledRunPausedWhenOnlyWorkspaceFundingIsUnavailable() {
        batch.setOrigin(ReviewBatchOrigin.SCHEDULE);
        batch.setKind("scm.repository");
        batch.setBudgetUsd(BigDecimal.ONE);
        when(scope.next(1, batch.getId(), null, 1)).thenReturn(List.of(6L));
        when(spend.remaining(batch)).thenReturn(new ReviewBatchSpend.Snapshot(BigDecimal.ONE, true));
        when(budget.canRequest(1, ReviewKind.SCAN, BigDecimal.ONE)).thenReturn(false);

        driver.advance(batch);

        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.PAUSED);
        assertThat(batch.getPauseReason()).isEqualTo(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
        assertThat(batch.getFinishedAt()).isNull();
    }

    @Test
    void exhaustedBudgetPausesWithoutAdvancingCursor() {
        batch.setUpdatedAt(now.minusSeconds(3600));
        when(budget.canRequest(1, ReviewKind.ITEM, null)).thenReturn(false);
        driver.advance(batch);
        assertThat(batch.getUpdatedAt()).isEqualTo(now);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.PAUSED);
        assertThat(batch.getPauseReason()).isEqualTo(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
        assertThat(batch.getCursorItemId()).isNull();
        verifyNoInteractions(submitter);
    }

    @Test
    void resumedBudgetContinuesFromTheStoredCursor() {
        batch.setStatus(ReviewBatchStatus.PAUSED);
        batch.setPauseReason(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
        batch.setCursorItemId(5L);
        when(scope.next(1, batch.getId(), 5L, 2)).thenReturn(List.of(6L));
        when(submitter.offer(batch, 6, null)).thenReturn(new ReviewBatchSubmitter.SubmissionOutcome.Submitted());
        driver.advance(batch);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.RUNNING);
        assertThat(batch.getPauseReason()).isNull();
        assertThat(batch.getCursorItemId()).isEqualTo(6L);
        assertThat(batch.getSubmittedCount()).isOne();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReviewBatchPauseReason.class,
            names = {"REVIEW_MODEL_UNBOUND", "WORKSPACE_UNAVAILABLE"})
    void shouldContinueFromTheStoredCursorWhenAPrerequisiteRecovers(ReviewBatchPauseReason reason) {
        batch.setCursorItemId(5L);
        when(scope.next(1, batch.getId(), 5L, 2)).thenReturn(List.of(6L));
        if (reason == ReviewBatchPauseReason.REVIEW_MODEL_UNBOUND) {
            when(readiness.hasRunnableAgent(1L)).thenReturn(false);
        } else {
            workspace.suspend();
        }
        driver.advance(batch);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.PAUSED);
        assertThat(batch.getPauseReason()).isEqualTo(reason);
        assertThat(batch.getCursorItemId()).isEqualTo(5L);

        when(readiness.hasRunnableAgent(1L)).thenReturn(true);
        workspace.resume();
        when(submitter.offer(batch, 6, null)).thenReturn(new ReviewBatchSubmitter.SubmissionOutcome.Submitted());

        driver.advance(batch);

        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.RUNNING);
        assertThat(batch.getPauseReason()).isNull();
        assertThat(batch.getCursorItemId()).isEqualTo(6L);
        assertThat(batch.getSubmittedCount()).isOne();
    }

    @Test
    void oneFailedItemDoesNotStopTheNextTarget() {
        when(scope.next(1, batch.getId(), null, 2)).thenReturn(List.of(1L, 2L));
        when(submitter.offer(batch, 1, null)).thenThrow(new IllegalStateException("unavailable"));
        when(submitter.offer(batch, 2, null)).thenReturn(new ReviewBatchSubmitter.SubmissionOutcome.Submitted());
        driver.advance(batch);
        assertThat(batch.getFailedCount()).isOne();
        assertThat(batch.getSubmittedCount()).isOne();
        assertThat(batch.getCursorItemId()).isEqualTo(2L);
    }

    @Test
    void emptyFrozenScopeCompletesBatch() {
        when(scope.next(1, batch.getId(), null, 2)).thenReturn(List.of());
        driver.advance(batch);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.COMPLETED);
        assertThat(batch.getFinishedAt()).isEqualTo(now);
    }

    @Test
    void cancelledBatchDoesNotOfferMoreWork() {
        batch.setStatus(ReviewBatchStatus.CANCELLED);
        driver.advance(batch);
        verifyNoInteractions(submitter, scope);
    }

    @Test
    void missingModelHasItsOwnPauseReason() {
        when(readiness.hasRunnableAgent(1L)).thenReturn(false);
        driver.advance(batch);
        assertThat(batch.getPauseReason()).isEqualTo(ReviewBatchPauseReason.REVIEW_MODEL_UNBOUND);
    }

    @Test
    void missingWorkspaceHasItsOwnPauseReason() {
        when(workspaces.findById(1L)).thenReturn(Optional.empty());
        driver.advance(batch);
        assertThat(batch.getPauseReason()).isEqualTo(ReviewBatchPauseReason.WORKSPACE_UNAVAILABLE);
    }

    @Test
    void competingScanKeepsTheTargetAndDoesNotRecordAFailure() {
        batch.setKind("scm.repository");
        when(budget.canRequest(1, ReviewKind.SCAN, null)).thenReturn(true);
        when(reviews.existsByWorkspaceIdAndKindAndStatusIn(
                        ArgumentMatchers.eq(1L),
                        ArgumentMatchers.eq(ReviewKind.SCAN),
                        ArgumentMatchers.anyCollection()))
                .thenReturn(false);
        when(scope.next(1, batch.getId(), null, 1)).thenReturn(List.of(6L));
        when(submitter.offer(batch, 6, null)).thenReturn(new ReviewBatchSubmitter.SubmissionOutcome.Deferred());
        driver.advance(batch);
        assertThat(batch.getCursorItemId()).isNull();
        assertThat(batch.getFailedCount()).isZero();
        assertThat(batch.getSubmittedCount()).isZero();
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.RUNNING);
        assertThat(batch.getPauseReason()).isNull();
    }

    @Test
    void shouldRetainTheNextItemWhenBudgetIsExhaustedPartwayThroughATick() {
        when(scope.next(1, batch.getId(), null, 2)).thenReturn(List.of(1L, 2L));
        when(submitter.offer(batch, 1, null)).thenReturn(new ReviewBatchSubmitter.SubmissionOutcome.Submitted());
        when(submitter.offer(batch, 2, null))
                .thenReturn(new ReviewBatchSubmitter.SubmissionOutcome.Paused(ReviewBatchPauseReason.BUDGET_EXHAUSTED));
        driver.advance(batch);
        assertThat(batch.getCursorItemId()).isEqualTo(1L);
        assertThat(batch.getSubmittedCount()).isOne();
        assertThat(batch.getFailedCount()).isZero();
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.PAUSED);
        assertThat(batch.getPauseReason()).isEqualTo(ReviewBatchPauseReason.BUDGET_EXHAUSTED);
    }

    @Test
    void shouldMoveAWaitingScanBehindOtherRunningBatches() {
        batch.setKind("scm.repository");
        batch.setUpdatedAt(now.minusSeconds(3600));
        when(reviews.existsByWorkspaceIdAndKindAndStatusIn(
                        ArgumentMatchers.eq(1L),
                        ArgumentMatchers.eq(ReviewKind.SCAN),
                        ArgumentMatchers.anyCollection()))
                .thenReturn(true);
        driver.advance(batch);
        assertThat(batch.getUpdatedAt()).isEqualTo(now);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.RUNNING);
        assertThat(batch.getCursorItemId()).isNull();
        verifyNoInteractions(submitter, scope);
    }

    @Test
    void shouldKeepAnAdministrativePauseWhenBudgetReturns() {
        batch.setStatus(ReviewBatchStatus.PAUSED);
        batch.setPauseReason(ReviewBatchPauseReason.PAUSED_BY_ADMIN);
        driver.advance(batch);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.PAUSED);
        assertThat(batch.getPauseReason()).isEqualTo(ReviewBatchPauseReason.PAUSED_BY_ADMIN);
        verifyNoInteractions(submitter, scope);
    }

    @Test
    void shouldAdvancePastAnUnavailableRepository() {
        batch.setKind("scm.repository");
        when(budget.canRequest(1, ReviewKind.SCAN, null)).thenReturn(true);
        when(scope.next(1, batch.getId(), null, 1)).thenReturn(List.of(6L));
        when(submitter.offer(batch, 6, null)).thenReturn(new ReviewBatchSubmitter.SubmissionOutcome.Skipped());
        driver.advance(batch);
        assertThat(batch.getCursorItemId()).isEqualTo(6L);
        assertThat(batch.getSkippedCount()).isOne();
        assertThat(batch.getFailedCount()).isZero();
        when(scope.next(1, batch.getId(), 6L, 1)).thenReturn(List.of());
        driver.advance(batch);
        assertThat(batch.getStatus()).isEqualTo(ReviewBatchStatus.COMPLETED);
    }
}
