package de.tum.cit.aet.hephaestus.integration.scm.github.workspace;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceStatusFixture;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.core.task.AsyncTaskExecutor;

class GitHubWorkspaceProvisioningAdapterDeletionTest extends BaseUnitTest {

    private static final long INSTALLATION_ID = 5001L;

    @Mock
    private GitHubLifecycleListener githubLifecycleListener;

    @Mock
    private WorkspaceRepositoryMonitorService repositoryMonitorService;

    @Mock
    private WorkspaceScopeFilter workspaceScopeFilter;

    @Mock
    private GitHubWorkspaceDataSyncTrigger dataSyncTrigger;

    @Mock
    private AsyncTaskExecutor monitoringExecutor;

    private GitHubWorkspaceProvisioningAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GitHubWorkspaceProvisioningAdapter(
                githubLifecycleListener,
                repositoryMonitorService,
                workspaceScopeFilter,
                dataSyncTrigger,
                monitoringExecutor,
                mock());
    }

    @Test
    @DisplayName("installation.deleted routes through the real purge instead of writing PURGED")
    void onInstallationDeleted_runsTheWorkspacePurgeChain() {
        adapter.onInstallationDeleted(INSTALLATION_ID);

        verify(githubLifecycleListener).purgeWorkspaceForInstallation(INSTALLATION_ID);
        // A bare status write is exactly the defect: it leaves the contributors unrun.
        verify(githubLifecycleListener, never()).suspendForInstallation(anyLong());
        verify(githubLifecycleListener, never()).resumeForInstallation(anyLong());
    }

    @Test
    @DisplayName("the bespoke NATS-stop and monitor sweep are dropped — the purge chain does both")
    void onInstallationDeleted_doesNotDuplicateWorkAlreadyDoneByThePurgeChain() {
        adapter.onInstallationDeleted(INSTALLATION_ID);

        // purgeWorkspace step 1 stops the consumer; ScmWorkspacePurgeAdapter (order -200) drops the
        // monitors through the orphan-guarded cascade, which — unlike
        // removeAllRepositoriesFromMonitor
        // — will not delete a repository another workspace still monitors.
        verify(repositoryMonitorService, never()).removeAllRepositoriesFromMonitor(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("a null installation id is a silent no-op, not a purge")
    void onInstallationDeleted_withoutAnInstallationId_purgesNothing() {
        adapter.onInstallationDeleted(null);

        verifyNoInteractions(githubLifecycleListener, repositoryMonitorService);
    }

    @Test
    @DisplayName("suspension still uses the plain status transition")
    void onInstallationSuspended_stillWritesStatusOnly() {
        adapter.onInstallationSuspended(INSTALLATION_ID);

        verify(githubLifecycleListener).suspendForInstallation(INSTALLATION_ID);
        verify(githubLifecycleListener, never()).purgeWorkspaceForInstallation(anyLong());
    }

    @Test
    void staleActivationCannotRestartWorkForAPurgedWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(11L);
        WorkspaceStatusFixture.markPurged(workspace);
        when(githubLifecycleListener.resumeForInstallation(INSTALLATION_ID)).thenReturn(Optional.of(workspace));

        adapter.onInstallationActivated(INSTALLATION_ID);

        verifyNoInteractions(dataSyncTrigger, monitoringExecutor);
    }
}
