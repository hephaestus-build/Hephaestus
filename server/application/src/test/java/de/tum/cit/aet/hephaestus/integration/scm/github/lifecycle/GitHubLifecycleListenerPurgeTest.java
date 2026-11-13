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

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener.AccountKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceStatusFixture;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;

class GitHubLifecycleListenerPurgeTest extends BaseUnitTest {

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

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private OrganizationService organizationService;

    @Mock
    private IdentityProviderRepository gitProviderRepository;

    private GitHubLifecycleListener listener;

    @BeforeEach
    void setUp() {
        listener = new GitHubLifecycleListener(
                workspaceRepository,
                mock(),
                repositoryRepository,
                mock(),
                gitProviderRepository,
                mock(),
                mock(),
                mock(),
                gitHubAppTokenService,
                organizationService,
                connectionService,
                workspaceLifecycleService,
                mock(),
                mock(),
                mock());
    }

    @ParameterizedTest
    @EnumSource(
            value = Workspace.WorkspaceStatus.class,
            names = {"SUSPENDED", "ACTIVE"})
    void statusTransitionStopsConsumptionOnlyForSuspension(Workspace.WorkspaceStatus status) {
        Workspace workspace = workspace(11L, "acme");
        boolean resume = status == Workspace.WorkspaceStatus.ACTIVE;
        if (resume) workspace.suspend();
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(workspace)).thenReturn(workspace);

        assertThat(
                        resume
                                ? listener.resumeForInstallation(INSTALLATION_ID)
                                : listener.suspendForInstallation(INSTALLATION_ID))
                .contains(workspace);

        assertThat(workspace.getStatus()).isEqualTo(status);
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

        verify(connectionService)
                .disconnectForErasure(eq(connection), any(ConnectionService.TransitionRequest.class), any(), any());
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
    void installationEventsCannotReactivateAPurgedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        WorkspaceStatusFixture.markPurged(workspace);
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));

        Workspace result = listener.createOrUpdateFromInstallation(
                INSTALLATION_ID, 99L, "acme", AccountKind.ORGANIZATION, null, RepositorySelection.ALL);

        assertThat(result).isNull();
        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void newInstallationCannotReactivateThePurgedWorkspaceOfTheSameOrganization() {
        Workspace workspace = workspace(11L, "acme");
        workspace.setAccountLogin("acme");
        WorkspaceStatusFixture.markPurged(workspace);
        IdentityProvider github = mock();
        when(github.getId()).thenReturn(3L);
        when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com"))
                .thenReturn(Optional.of(github));
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.empty());
        when(organizationService.findId(99L, 3L)).thenReturn(Optional.of(7L));
        when(workspaceRepository.findByOrganizationIdForUpdate(7L)).thenReturn(Optional.of(workspace));

        Workspace result = listener.createOrUpdateFromInstallation(
                INSTALLATION_ID, 99L, "acme", AccountKind.ORGANIZATION, null, RepositorySelection.ALL);

        assertThat(result).isNull();
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void statusEventsCannotReactivateAPurgedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        WorkspaceStatusFixture.markPurged(workspace);
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));

        assertThat(listener.resumeForInstallation(INSTALLATION_ID)).contains(workspace);
        assertThat(listener.suspendForInstallation(INSTALLATION_ID)).contains(workspace);

        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void repositorySelectionReplayCannotMutateAPurgedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        WorkspaceStatusFixture.markPurged(workspace);
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
        WorkspaceStatusFixture.markPurged(workspace);
        workspace.setAccountLogin("acme");
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));

        listener.handleAccountRename(INSTALLATION_ID, "acme", "renamed");

        assertThat(workspace.getAccountLogin()).isEqualTo("acme");
        verify(workspaceRepository, never()).save(workspace);
    }

    @Test
    void shouldRefuseRepositoryRenameWhenTheGitHubProviderIsUnavailable() {
        Workspace workspace = workspace(11L, "acme");
        workspace.resume();
        workspace.setAccountLogin("acme");
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));
        when(gitProviderRepository.findByTypeAndServerUrl(
                        IdentityProviderType.GITHUB, PersonSourceNamespace.GITHUB_DEFAULT_URL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.handleAccountRename(INSTALLATION_ID, "acme", "renamed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("IdentityProvider for GitHub not found");

        verifyNoInteractions(repositoryRepository);
    }

    @Test
    void activationStillResumesALockedSuspendedWorkspace() {
        Workspace workspace = workspace(11L, "acme");
        workspace.suspend();
        when(workspaceRepository.findByInstallationIdForUpdate(INSTALLATION_ID)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(workspace)).thenReturn(workspace);

        assertThat(listener.resumeForInstallation(INSTALLATION_ID)).contains(workspace);

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
