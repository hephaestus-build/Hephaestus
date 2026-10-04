package de.tum.cit.aet.hephaestus.integration.slack.sync.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.slack.sync.SlackChannelHistorySyncService.WorkspaceSyncSummary;
import de.tum.cit.aet.hephaestus.integration.slack.sync.SlackDataSyncScheduler;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

@Tag("unit")
class SlackIntegrationSyncRunnerTest extends BaseUnitTest {

    private static final long WS = 55L;
    private static final IntegrationRef REF = new IntegrationRef(IntegrationKind.SLACK, WS, "T999");

    @Mock
    private SlackDataSyncScheduler dataSyncScheduler;

    @Mock
    private SyncJobHandle handle;

    private SlackIntegrationSyncRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SlackIntegrationSyncRunner(dataSyncScheduler);
    }

    @Test
    void reconcile_delegatesToSyncWorkspaceNowAndReportsProgressFromTheSummary() {
        WorkspaceSyncSummary summary = new WorkspaceSyncSummary(3, 2, 1, 7L, 4, false, 0);
        when(dataSyncScheduler.syncWorkspaceNow(WS, handle)).thenReturn(summary);

        runner.reconcile(REF, handle, SyncJobType.RECONCILIATION);

        verify(dataSyncScheduler).syncWorkspaceNow(WS, handle);
        verify(handle).progress(eq(3), eq(3), any(SyncProgress.class));
        verify(handle, never()).reportWarnings();
    }

    @Test
    void reconcile_reportsWarningsWhenTheSummaryHasFailedChannels() {
        WorkspaceSyncSummary partial = new WorkspaceSyncSummary(4, 1, 2, 2L, 4, true, 1);
        when(dataSyncScheduler.syncWorkspaceNow(WS, handle)).thenReturn(partial);

        runner.reconcile(REF, handle, SyncJobType.RECONCILIATION);

        verify(handle).progress(eq(3), eq(4), any(SyncProgress.class));
        verify(handle).reportWarnings();
    }

    @Test
    void reconcile_reportsWarningsWhenTheRequestBudgetIsExhausted() {
        WorkspaceSyncSummary partial = new WorkspaceSyncSummary(3, 1, 2, 2L, 4, true, 0);
        when(dataSyncScheduler.syncWorkspaceNow(WS, handle)).thenReturn(partial);

        runner.reconcile(REF, handle, SyncJobType.RECONCILIATION);

        verify(handle).reportWarnings();
    }

    @Test
    void reconcile_reportsCancellationObservedByTheSync() {
        WorkspaceSyncSummary summary = new WorkspaceSyncSummary(3, 1, 2, 1L, 1, false, 0);
        when(dataSyncScheduler.syncWorkspaceNow(WS, handle)).thenReturn(summary);
        when(handle.isCancellationRequested()).thenReturn(true);

        runner.reconcile(REF, handle, SyncJobType.RECONCILIATION);

        verify(handle).reportCancelled();
    }

    @Test
    void progressDetail_carriesEveryFieldOfTheSummary() {
        WorkspaceSyncSummary summary = new WorkspaceSyncSummary(3, 2, 1, 7L, 4, true, 1);

        SyncProgress detail = SlackIntegrationSyncRunner.progressDetail(summary);

        assertThat(detail.phase()).isEqualTo(SyncPhase.CHANNELS);
        assertThat(detail.unitsCompleted()).isEqualTo(3);
        assertThat(detail.unitsTotal()).isEqualTo(3);
        assertThat(detail.currentStep())
                .isEqualTo("Hephaestus synced 2 of 3 channels. Hephaestus ingested 7 messages."
                        + " The sync skipped 1 channel. The sync failed for 1 of them."
                        + " The request budget ran out.");
    }
}
