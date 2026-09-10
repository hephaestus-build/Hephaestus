package de.tum.cit.aet.hephaestus.agent.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

/**
 * The campaign walk: a run that cannot afford to continue stops rather than thinning itself, so
 * "not reviewed" and "reviewed, nothing found" stay distinguishable.
 */
@DisplayName("Review backfill driver")
class ReviewBackfillDriverTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 3L;

    @Mock
    private ReviewBackfillRunRepository runRepository;

    @Mock
    private ReviewBackfillScopeRepository scopeRepository;

    @Mock
    private ReviewBackfillSubmitter submitter;

    @Mock
    private WorkspaceAgentBindingRepository bindingRepository;

    @Mock
    private LlmBudgetService llmBudgetService;

    private final ReviewBackfillProperties properties =
            new ReviewBackfillProperties(3, Duration.ofDays(400), 5000, Duration.ofDays(90));

    private ReviewBackfillDriver driver() {
        return new ReviewBackfillDriver(
                runRepository, scopeRepository, submitter, bindingRepository, llmBudgetService, properties);
    }

    @Test
    void aBatchAdvancesTheCursorToTheLastArtifactItOffered() {
        ReviewBackfillRun run = running();
        fundedAndEnabled();
        when(scopeRepository.findPullRequestIds(eq(WORKSPACE_ID), any(), any(), eq(0L), any()))
                .thenReturn(List.of(10L, 11L, 12L));
        when(submitter.offer(eq(run), anyLong())).thenReturn(ReviewBackfillSubmitter.Outcome.SUBMITTED);

        driver().advance(run);

        assertThat(run.getCursorArtifactId()).isEqualTo(12L);
        assertThat(run.getSubmittedCount()).isEqualTo(3);
        assertThat(run.getPassedCount()).isZero();
        assertThat(run.getStatus()).isEqualTo(ReviewBackfillStatus.RUNNING);
    }

    @Test
    void anArtifactWalkedPastIsCountedRatherThanForgotten() {
        ReviewBackfillRun run = running();
        fundedAndEnabled();
        when(scopeRepository.findPullRequestIds(eq(WORKSPACE_ID), any(), any(), eq(0L), any()))
                .thenReturn(List.of(10L, 11L));
        when(submitter.offer(run, 10L)).thenReturn(ReviewBackfillSubmitter.Outcome.SUBMITTED);
        when(submitter.offer(run, 11L)).thenReturn(ReviewBackfillSubmitter.Outcome.PASSED);

        driver().advance(run);

        assertThat(run.getSubmittedCount()).isEqualTo(1);
        assertThat(run.getPassedCount()).isEqualTo(1);
    }

    @Test
    void anExhaustedBudgetPausesTheRunWithoutMovingTheCursor() {
        ReviewBackfillRun run = running();
        run.setCursorArtifactId(9L);
        enabledBinding();
        when(llmBudgetService.blockSubmission(any(), any(), any())).thenReturn(true);

        driver().advance(run);

        assertThat(run.getStatus()).isEqualTo(ReviewBackfillStatus.PAUSED);
        assertThat(run.getPauseReason()).isEqualTo(ReviewBackfillPauseReason.BUDGET_EXHAUSTED);
        assertThat(run.getCursorArtifactId()).isEqualTo(9L);
        Mockito.verifyNoInteractions(submitter);
        verify(scopeRepository, never()).findPullRequestIds(anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    void aWorkspaceWithNoEnabledBindingPausesRatherThanFailing() {
        ReviewBackfillRun run = running();
        when(bindingRepository.findByWorkspaceIdWithModels(WORKSPACE_ID)).thenReturn(java.util.List.of());

        driver().advance(run);

        assertThat(run.getStatus()).isEqualTo(ReviewBackfillStatus.PAUSED);
        assertThat(run.getPauseReason()).isEqualTo(ReviewBackfillPauseReason.REVIEW_MODEL_UNBOUND);
    }

    @Test
    void aPausedRunResumesByItselfOnceTheBudgetReturns() {
        ReviewBackfillRun run = running();
        run.transitionTo(ReviewBackfillStatus.PAUSED, ReviewBackfillPauseReason.BUDGET_EXHAUSTED);
        fundedAndEnabled();
        when(scopeRepository.findPullRequestIds(eq(WORKSPACE_ID), any(), any(), eq(0L), any()))
                .thenReturn(List.of(5L));
        when(submitter.offer(eq(run), anyLong())).thenReturn(ReviewBackfillSubmitter.Outcome.SUBMITTED);

        driver().advance(run);

        assertThat(run.getStatus()).isEqualTo(ReviewBackfillStatus.RUNNING);
        assertThat(run.getPauseReason()).isNull();
        assertThat(run.getCursorArtifactId()).isEqualTo(5L);
    }

    @Test
    void anEmptyBatchMeansTheScopeIsCoveredAndTheRunIsComplete() {
        ReviewBackfillRun run = running();
        run.setCursorArtifactId(99L);
        fundedAndEnabled();
        when(scopeRepository.findPullRequestIds(eq(WORKSPACE_ID), any(), any(), eq(99L), any()))
                .thenReturn(List.of());

        driver().advance(run);

        assertThat(run.getStatus()).isEqualTo(ReviewBackfillStatus.COMPLETED);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void aCancelledRunIsNotAdvanced() {
        ReviewBackfillRun run = running();
        run.transitionTo(ReviewBackfillStatus.CANCELLED, null);

        driver().advance(run);

        Mockito.verifyNoInteractions(submitter, scopeRepository, llmBudgetService, bindingRepository);
    }

    private void enabledBinding() {
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setEnabled(true);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        when(bindingRepository.findByWorkspaceIdWithModels(WORKSPACE_ID)).thenReturn(java.util.List.of(binding));
    }

    private void fundedAndEnabled() {
        enabledBinding();
        when(llmBudgetService.blockSubmission(any(), any(), any())).thenReturn(false);
    }

    private ReviewBackfillRun running() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.getFeatures().setPracticesEnabled(true);

        ReviewBackfillRun run = new ReviewBackfillRun();
        run.setId(UUID.randomUUID());
        run.setWorkspace(workspace);
        run.setArtifactKind(ArtifactKinds.PULL_REQUEST.value());
        run.setFromAt(Instant.parse("2026-07-01T00:00:00Z"));
        run.setToAt(Instant.parse("2026-08-01T00:00:00Z"));
        run.setStatus(ReviewBackfillStatus.RUNNING);
        run.setEstimatedArtifacts(3);
        run.setRequestedByAccountId(1L);
        run.setConfirmedByAccountId(1L);
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        return run;
    }
}
