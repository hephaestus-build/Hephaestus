package de.tum.cit.aet.hephaestus.integration.scm.github.sync.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.GithubDataSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.BackfillPageObserver;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

class GithubIntegrationSyncRunnerTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;

    @Mock
    private GithubDataSyncScheduler dataSyncScheduler;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private GitHubHistoricalBackfillService backfillService;

    @Mock
    private SyncJobHandle handle;

    private GithubIntegrationSyncRunner runner;
    private IntegrationRef ref;

    @BeforeEach
    void setUp() {
        SyncSchedulerProperties properties = new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new BackfillProperties(true, 50, 100, 60),
                new FilterProperties(Set.of(), Set.of(), Set.of()),
                null,
                null);
        runner = new GithubIntegrationSyncRunner(dataSyncScheduler, syncTargetProvider, backfillService, properties);
        ref = new IntegrationRef(IntegrationKind.GITHUB, WORKSPACE_ID, "100");
    }

    private static SyncTarget pendingTarget(long id, String repoName) {
        // issueBackfillHighWaterMark left null (not initialized) -> isBackfillComplete() == false
        return SyncTargetTestBuilder.syncTarget()
                .id(id)
                .scopeId(WORKSPACE_ID)
                .authMode(AuthMode.INSTALLATION_APP)
                .repositoryNameWithOwner(repoName)
                .build();
    }

    @Nested
    class Reconcile {

        @Test
        void reportsCancelledWhenTheHandleIsStillCancelledOnReturn() {
            when(handle.isCancellationRequested()).thenReturn(true);

            runner.reconcile(ref, handle, SyncJobType.RECONCILIATION);

            verify(dataSyncScheduler).syncWorkspaceNow(WORKSPACE_ID, handle, SyncJobType.RECONCILIATION);
            verify(handle).reportCancelled();
        }

        @Test
        void forwardsTheJobTypeSoInitialDoesNotSweep() {
            // The scheduler decides the sweep off this value; dropping it here would silently make
            // INITIAL and RECONCILIATION identical again — the exact bug this change fixes.
            runner.reconcile(ref, handle, SyncJobType.INITIAL);

            verify(dataSyncScheduler).syncWorkspaceNow(WORKSPACE_ID, handle, SyncJobType.INITIAL);
        }
    }

    @Nested
    class Backfill {

        @Test
        void supportsBackfill_isStillOfferedWhenTheScheduledCycleIsDisabled() {
            SyncSchedulerProperties cycleDisabled = new SyncSchedulerProperties(
                    true,
                    7,
                    "0 0 3 * * *",
                    15,
                    new BackfillProperties(false, 50, 100, 60),
                    new FilterProperties(Set.of(), Set.of(), Set.of()),
                    null,
                    null);
            GithubIntegrationSyncRunner runnerWithCycleDisabled = new GithubIntegrationSyncRunner(
                    dataSyncScheduler, syncTargetProvider, backfillService, cycleDisabled);

            // hephaestus.sync.backfill.enabled gates the scheduled cycle, not the capability —
            // GitHubHistoricalBackfillService#runBackfillBatch deliberately ignores it, so an
            // administratively paused cycle must stay resumable by hand.
            assertThat(runnerWithCycleDisabled.supportsBackfill()).isTrue();
        }

        @Test
        void runsUntilNoRepositoriesArePending() {
            SyncTarget targetA = pendingTarget(1L, "acme/repo-a");
            SyncTarget targetB = pendingTarget(2L, "acme/repo-b");
            // The runner re-reads the scope once per outer pass and again after each repository's batch
            // (to fold in the checkpoints that batch just persisted): 1 + 2 reads for the first pass,
            // then an empty scope so the second pass finds nothing pending and stops.
            when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID))
                    .thenReturn(List.of(targetA, targetB))
                    .thenReturn(List.of(targetA, targetB))
                    .thenReturn(List.of(targetA, targetB))
                    .thenReturn(List.of());
            when(handle.isCancellationRequested()).thenReturn(false);
            when(backfillService.runBackfillBatch(any(), anyInt(), any())).thenReturn(true);

            runner.backfill(ref, handle);

            verify(backfillService).runBackfillBatch(eq(targetA), eq(50), any());
            verify(backfillService).runBackfillBatch(eq(targetB), eq(50), any());
            // itemsTotal stays null because neither target has captured a high-water mark yet.
            verify(handle, times(2)).progress(any(), isNull(), any());
            verify(handle, never()).reportCancelled();
        }

        @Test
        void stopsProcessingRemainingRepositoriesOnceCancelled() {
            SyncTarget targetA = pendingTarget(1L, "acme/repo-a");
            SyncTarget targetB = pendingTarget(2L, "acme/repo-b");
            SyncTarget targetC = pendingTarget(3L, "acme/repo-c");
            when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID))
                    .thenReturn(List.of(targetA, targetB, targetC));
            // outer-check(false) -> inner: targetA(false, processed) -> targetB(true, cancel: break) ->
            // outer-check(true, stop)
            when(handle.isCancellationRequested())
                    .thenReturn(false)
                    .thenReturn(false)
                    .thenReturn(true)
                    .thenReturn(true);
            when(backfillService.runBackfillBatch(any(), anyInt(), any())).thenReturn(true);

            runner.backfill(ref, handle);

            verify(backfillService, times(1)).runBackfillBatch(any(), anyInt(), any());
            verify(backfillService).runBackfillBatch(eq(targetA), anyInt(), any());
            verify(backfillService, never()).runBackfillBatch(eq(targetB), anyInt(), any());
            verify(backfillService, never()).runBackfillBatch(eq(targetC), anyInt(), any());
            verify(handle).reportCancelled();
        }

        @Test
        void cancelledBeforeFirstBatch_neverCallsRunBackfillBatch() {
            when(handle.isCancellationRequested()).thenReturn(true);

            runner.backfill(ref, handle);

            verify(backfillService, never()).runBackfillBatch(any(), anyInt(), any());
            verify(syncTargetProvider, never()).getSyncTargetsForScope(any());
            verify(handle).reportCancelled();
        }

        @Test
        void noProgressAcrossAllPendingRepositories_stopsInsteadOfSpinning() {
            SyncTarget targetA = pendingTarget(1L, "acme/repo-a");
            SyncTarget targetB = pendingTarget(2L, "acme/repo-b");
            when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID)).thenReturn(List.of(targetA, targetB));
            when(handle.isCancellationRequested()).thenReturn(false);
            when(backfillService.runBackfillBatch(any(), anyInt(), any())).thenReturn(false);

            runner.backfill(ref, handle);

            // Exactly one outer pass: one scope read to open it, plus one checkpoint refresh per
            // repository. A second pass would read the scope a fourth time.
            verify(syncTargetProvider, times(3)).getSyncTargetsForScope(WORKSPACE_ID);
            verify(backfillService, times(2)).runBackfillBatch(any(), anyInt(), any());
            verify(handle).reportWarnings();
        }
    }

    /**
     * The runner's half of the per-page contract — the actual fix for a backfill that looked frozen for
     * minutes and then completed all at once.
     */
    @Nested
    class PerPageReporting {

        private SyncTarget initializedTarget(long id, String repoName, Integer issueHwm, Integer issueCheckpoint) {
            return SyncTargetTestBuilder.syncTarget()
                    .id(id)
                    .scopeId(WORKSPACE_ID)
                    .authMode(AuthMode.INSTALLATION_APP)
                    .repositoryNameWithOwner(repoName)
                    .issueBackfillHighWaterMark(issueHwm)
                    .issueBackfillCheckpoint(issueCheckpoint)
                    .pullRequestBackfillHighWaterMark(0)
                    .pullRequestBackfillCheckpoint(0)
                    .build();
        }

        /** Drives the observer the runner hands to the backfill service, as pages would. */
        private void answerWithPages(SyncTarget target, int... lowestNumbersSeen) {
            when(backfillService.runBackfillBatch(eq(target), anyInt(), any())).thenAnswer(inv -> {
                BackfillPageObserver observer = inv.getArgument(2);
                for (int i = 0; i < lowestNumbersSeen.length; i++) {
                    observer.onPageComplete(
                            target.id(),
                            target.repositoryNameWithOwner(),
                            SyncPhase.ISSUES,
                            lowestNumbersSeen[i],
                            (i + 1) * 100);
                }
                return true;
            });
        }

        @Test
        void reportsOncePerVendorPageRatherThanOncePerBatch() {
            SyncTarget target = initializedTarget(1L, "acme/repo", 100, 100);
            when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID))
                    .thenReturn(List.of(target))
                    .thenReturn(List.of());
            answerWithPages(target, 80, 60, 40);

            runner.backfill(ref, handle);

            // Three pages => three reports, plus the batch-boundary report. The runner deliberately does
            // not throttle: the handle owns the write budget.
            verify(handle, times(4)).progress(any(), any(), any(SyncProgress.class));
        }

        @Test
        void reportsDeterminateTotalsFromThePersistedHighWaterMarks() {
            SyncTarget target = initializedTarget(1L, "acme/repo", 100, 100);
            when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID))
                    .thenReturn(List.of(target))
                    .thenReturn(List.of());
            answerWithPages(target, 60);

            runner.backfill(ref, handle);

            ArgumentCaptor<Integer> processed = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> total = ArgumentCaptor.forClass(Integer.class);
            verify(handle, atLeastOnce()).progress(processed.capture(), total.capture(), any(SyncProgress.class));

            // Determinate, so the UI can draw a real bar instead of an eternal spinner: issues walked
            // from #100 down to #60 is 40 of 100 done.
            assertThat(total.getAllValues().getFirst()).isEqualTo(100);
            assertThat(processed.getAllValues().getFirst()).isEqualTo(40);
        }

        @Test
        void reportsAHumanNarrativeNamingTheRepositoryAndTheNumberRange() {
            SyncTarget target = initializedTarget(1L, "ls1intum/Artemis", 4812, 4812);
            when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID))
                    .thenReturn(List.of(target))
                    .thenReturn(List.of());
            answerWithPages(target, 3200);

            runner.backfill(ref, handle);

            ArgumentCaptor<SyncProgress> captor = ArgumentCaptor.forClass(SyncProgress.class);
            verify(handle, atLeastOnce()).progress(any(), any(), captor.capture());
            SyncProgress first = captor.getAllValues().getFirst();

            // currentStep is the one string the UI renders — it has to stand alone as a sentence.
            assertThat(first.currentStep()).isEqualTo("Backfilling ls1intum/Artemis — issues #4812 → #3200");
            assertThat(first.phase()).isEqualTo(SyncPhase.ISSUES);
            assertThat(first.currentRepository()).isEqualTo("ls1intum/Artemis");
        }

        @Test
        void uninitializedTarget_reportsNullTotalRatherThanFakingADenominator() {
            SyncTarget target = pendingTarget(1L, "acme/repo");
            when(syncTargetProvider.getSyncTargetsForScope(WORKSPACE_ID))
                    .thenReturn(List.of(target))
                    .thenReturn(List.of());
            answerWithPages(target, 60);

            runner.backfill(ref, handle);

            verify(handle, atLeastOnce()).progress(any(), isNull(), any(SyncProgress.class));
        }
    }
}
