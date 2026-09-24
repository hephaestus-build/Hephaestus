package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener.AccountKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GithubLifecycleListenerPurgeTest extends BaseUnitTest {

    private static final long INSTALLATION_ID = 5001L;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceLifecycleService workspaceLifecycleService;

    @Mock
    private GitHubAppTokenService gitHubAppTokenService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private Connection connection;

    private GithubLifecycleListener listener;

    @BeforeEach
    void setUp() {
        listener = new GithubLifecycleListener(
                mock(),
                workspaceRepository,
                mock(),
                mock(),
                mock(),
                mock(),
                mock(),
                mock(),
                mock(),
                gitHubAppTokenService,
                mock(),
                connectionService,
                workspaceLifecycleService,
                mock());
    }

    @Test
    @DisplayName("purgeWorkspaceForInstallation delegates to the canonical purge")
    void purgeWorkspaceForInstallation_delegatesToWorkspaceLifecycleService() {
        Workspace workspace = workspace(11L, "acme");
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITHUB, 11L, Long.toString(INSTALLATION_ID));
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));
        when(workspaceLifecycleService.purgeWorkspace("acme")).thenReturn(workspace);

        assertThat(listener.purgeWorkspaceForInstallation(INSTALLATION_ID)).contains(workspace);

        verify(connectionService).transition(eq(connection), any(ConnectionService.TransitionRequest.class));
    }

    @Test
    @DisplayName("the SPI uninstall hook purges rather than flipping a status")
    void onInstanceUninstalled_purgesTheWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));
        when(workspaceLifecycleService.purgeWorkspace("acme")).thenReturn(workspace);

        listener.onInstanceUninstalled(new IntegrationRef(IntegrationKind.GITHUB, 11L, Long.toString(INSTALLATION_ID)));

        verify(workspaceLifecycleService).purgeWorkspace("acme");
    }

    @Test
    @DisplayName("an installation with no bound workspace purges nothing")
    void purgeWorkspaceForInstallation_withoutAWorkspace_isASilentNoOp() {
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.empty());

        assertThat(listener.purgeWorkspaceForInstallation(INSTALLATION_ID)).isEmpty();

        verifyNoInteractions(workspaceLifecycleService);
    }

    @Test
    @DisplayName("updateWorkspaceStatus refuses PURGED outright")
    void updateWorkspaceStatus_rejectsPurgedSoItCannotBypassThePurgeChain() {
        assertThatThrownBy(() -> listener.updateWorkspaceStatus(INSTALLATION_ID, Workspace.WorkspaceStatus.PURGED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purgeWorkspaceForInstallation");

        verifyNoInteractions(workspaceRepository, workspaceLifecycleService);
    }

    @Test
    void installationEventsCannotReactivateAPurgedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));

        Workspace result = listener.createOrUpdateFromInstallation(
                INSTALLATION_ID, 99L, "acme", AccountKind.ORGANIZATION, null, RepositorySelection.ALL);

        assertThat(result).isNull();
        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void newInstallationCannotReplaceAPurgedWorkspaceWithTheSameAccount() {
        Workspace workspace = workspace(11L, "acme");
        workspace.setAccountLogin("acme");
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.empty());
        when(workspaceRepository.findByAccountLoginIgnoreCaseForUpdate("acme")).thenReturn(Optional.of(workspace));

        Workspace result = listener.createOrUpdateFromInstallation(
                INSTALLATION_ID, 99L, "acme", AccountKind.ORGANIZATION, null, RepositorySelection.ALL);

        assertThat(result).isNull();
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void statusEventsCannotReactivateAPurgedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));

        assertThat(listener.updateWorkspaceStatus(INSTALLATION_ID, Workspace.WorkspaceStatus.ACTIVE))
                .contains(workspace);

        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void repositorySelectionReplayCannotMutateAPurgedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        workspace.setRepositorySelection(RepositorySelection.SELECTED);
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));

        assertThat(listener.updateRepositorySelection(INSTALLATION_ID, RepositorySelection.ALL))
                .isEmpty();

        assertThat(workspace.getRepositorySelection()).isEqualTo(RepositorySelection.SELECTED);
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void renameReplayCannotMutateAPurgedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        workspace.setAccountLogin("acme");
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));

        listener.handleAccountRename(INSTALLATION_ID, "acme", "renamed");

        assertThat(workspace.getAccountLogin()).isEqualTo("acme");
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void activationStillResumesALockedSuspendedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        workspace.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(workspace)).thenReturn(workspace);

        assertThat(listener.updateWorkspaceStatus(INSTALLATION_ID, Workspace.WorkspaceStatus.ACTIVE))
                .contains(workspace);

        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
        verify(workspaceRepository).findByInstallationIdForUpdate(INSTALLATION_ID);
    }

    private static Workspace workspace(long id, String slug) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setWorkspaceSlug(slug);
        return workspace;
    }
}
