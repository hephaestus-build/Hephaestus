package de.tum.cit.aet.hephaestus.integration.outline.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class OutlineIntegrationSyncRunnerTest extends BaseUnitTest {

    private static final long WORKSPACE = 7L;

    @Mock
    private OutlineDocumentSyncScheduler syncScheduler;

    @Mock
    private SyncJobHandle handle;

    private OutlineIntegrationSyncRunner runner;

    @BeforeEach
    void setUp() {
        runner = new OutlineIntegrationSyncRunner(syncScheduler);
    }

    @Test
    void reconcile_delegatesToTheWorkspacePass_threadingTheJobHandle() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.OUTLINE, WORKSPACE, "team-1");

        runner.reconcile(ref, handle, SyncJobType.RECONCILIATION);

        verify(syncScheduler).syncWorkspaceNow(WORKSPACE, handle, SyncJobType.RECONCILIATION);
        verify(handle, never()).reportCancelled();
    }

    @Test
    void reconcile_reportsCancelledWhenTheHandleIsStillCancelledOnReturn() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.OUTLINE, WORKSPACE, "team-1");
        when(handle.isCancellationRequested()).thenReturn(true);

        runner.reconcile(ref, handle, SyncJobType.RECONCILIATION);

        verify(syncScheduler).syncWorkspaceNow(WORKSPACE, handle, SyncJobType.RECONCILIATION);
        verify(handle).reportCancelled();
    }

    @Test
    void reconcile_forwardsTheJobTypeRatherThanDroppingIt() {
        // The type gates tombstone-by-absence for Outline: an INITIAL run must not reach the sync path as a
        // RECONCILIATION sweep, or it would tombstone documents merely absent from a half-built mirror.
        IntegrationRef ref = new IntegrationRef(IntegrationKind.OUTLINE, WORKSPACE, "team-1");

        runner.reconcile(ref, handle, SyncJobType.INITIAL);

        verify(syncScheduler).syncWorkspaceNow(WORKSPACE, handle, SyncJobType.INITIAL);
    }

    @Test
    void reconcile_propagatesPartialFailuresAsWarnings() {
        doAnswer(invocation -> {
                    SyncExecutionHandle threaded = invocation.getArgument(1);
                    threaded.reportWarnings();
                    return null;
                })
                .when(syncScheduler)
                .syncWorkspaceNow(eq(WORKSPACE), any(SyncExecutionHandle.class), eq(SyncJobType.RECONCILIATION));

        runner.reconcile(
                new IntegrationRef(IntegrationKind.OUTLINE, WORKSPACE, "team-1"), handle, SyncJobType.RECONCILIATION);

        verify(handle).reportWarnings();
    }
}
