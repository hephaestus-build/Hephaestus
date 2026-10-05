package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewRepositoryTargetRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A credential the server cannot decrypt removes its own workspace from the sync enumerations
 * that span every workspace, while a lookup of that one workspace still fails with it.
 */
class WorkspaceSyncTargetProviderEnumerationTest extends BaseUnitTest {

    private static final ConnectionConfig.GitLabConfig GITLAB_CONFIG = new ConnectionConfig.GitLabConfig(
            "https://gitlab.example.com",
            null,
            null,
            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
            Set.of(),
            null);

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private WorkspaceScopeFilter workspaceScopeFilter;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private ObjectProvider<IntegrationNatsConsumer> natsConsumerService;

    private WorkspaceSyncTargetProvider provider;

    @BeforeEach
    void setUp() {
        provider = new WorkspaceSyncTargetProvider(
                workspaceRepository,
                repositoryToMonitorRepository,
                workspaceScopeFilter,
                connectionService,
                new NatsConnectionProperties(true, "nats://localhost:4222", null, null),
                natsConsumerService,
                mock(PracticeReviewRepositoryTargetRepository.class));
        lenient()
                .when(workspaceScopeFilter.isRepositoryAllowed(any(RepositoryToMonitor.class)))
                .thenReturn(true);
    }

    /** A workspace that monitors one repository; its sync target id is ten times the workspace id. */
    private static Workspace workspace(long id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setWorkspaceSlug("workspace-" + id);
        workspace.setDisplayName("Workspace " + id);
        workspace.setAccountLogin("org-" + id);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setId(id * 10);
        monitor.setNameWithOwner("org-" + id + "/repo");
        monitor.setWorkspace(workspace);
        workspace.getRepositoriesToMonitor().add(monitor);
        return workspace;
    }

    private static CredentialUnreadableException unreadable(IntegrationKind kind) {
        return new CredentialUnreadableException(50, kind, new IllegalStateException());
    }

    @Test
    void shouldReturnHealthyGitLabSessionsWhenAnotherWorkspaceCredentialIsUnreadable() {
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace(5), workspace(9), workspace(12)));
        when(workspaceScopeFilter.isWorkspaceAllowed(any(Workspace.class))).thenReturn(true);
        when(connectionService.findActiveProviderKind(anyLong())).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(connectionService.findActiveGitLabConfig(anyLong())).thenReturn(Optional.of(GITLAB_CONFIG));
        when(connectionService.findActiveBearerToken(5, IntegrationKind.GITLAB))
                .thenThrow(unreadable(IntegrationKind.GITLAB));
        when(connectionService.findActiveBearerToken(9, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(new BearerToken("glpat-nine", null)));
        when(connectionService.findActiveBearerToken(12, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(new BearerToken("glpat-twelve", null)));

        List<SyncSession> sessions = provider.getSyncSessions(IntegrationKind.GITLAB);

        assertThat(sessions)
                .extracting(SyncSession::scopeId, SyncSession::serverUrl)
                .containsExactly(tuple(9L, "https://gitlab.example.com"), tuple(12L, "https://gitlab.example.com"));
        assertThat(sessions.stream().flatMap(session -> session.syncTargets().stream()))
                .extracting(SyncTarget::id, SyncTarget::scopeId, SyncTarget::personalAccessToken, SyncTarget::authMode)
                .containsExactly(
                        tuple(90L, 9L, "glpat-nine", AuthMode.PERSONAL_ACCESS_TOKEN),
                        tuple(120L, 12L, "glpat-twelve", AuthMode.PERSONAL_ACCESS_TOKEN));
    }

    @Test
    void shouldReturnHealthyGitHubSessionsWhenAnotherWorkspaceCredentialIsUnreadable() {
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace(5), workspace(9), workspace(12)));
        when(workspaceScopeFilter.isWorkspaceAllowed(any(Workspace.class))).thenReturn(true);
        when(connectionService.findActiveProviderKind(anyLong())).thenReturn(Optional.of(IntegrationKind.GITHUB));
        when(connectionService.findActiveGitHubAppConfig(anyLong())).thenReturn(Optional.empty());
        when(connectionService.findActiveGitHubPatConfig(anyLong()))
                .thenReturn(Optional.of(new ConnectionConfig.GitHubPatConfig(null, null, Set.of())));
        when(connectionService.findActiveBearerToken(5, IntegrationKind.GITHUB))
                .thenThrow(unreadable(IntegrationKind.GITHUB));
        when(connectionService.findActiveBearerToken(9, IntegrationKind.GITHUB))
                .thenReturn(Optional.of(new BearerToken("ghp-nine", null)));
        // A GitHub App workspace mints installation tokens and has no stored token to read.
        when(connectionService.findActiveGitHubAppConfig(12))
                .thenReturn(Optional.of(new ConnectionConfig.GitHubAppConfig(4242L, null, null, Set.of())));

        List<SyncSession> sessions = provider.getSyncSessions(IntegrationKind.GITHUB);

        assertThat(sessions)
                .extracting(SyncSession::scopeId, SyncSession::installationId)
                .containsExactly(tuple(9L, null), tuple(12L, 4242L));
        assertThat(sessions.stream().flatMap(session -> session.syncTargets().stream()))
                .extracting(
                        SyncTarget::id,
                        SyncTarget::scopeId,
                        SyncTarget::personalAccessToken,
                        SyncTarget::authMode,
                        SyncTarget::installationId)
                .containsExactly(
                        tuple(90L, 9L, "ghp-nine", AuthMode.PERSONAL_ACCESS_TOKEN, null),
                        tuple(120L, 12L, null, AuthMode.INSTALLATION_APP, 4242L));
    }

    @Test
    void shouldReturnOwnSessionWhenAnotherWorkspaceCredentialIsUnreadable() {
        when(workspaceRepository.findById(9L)).thenReturn(Optional.of(workspace(9)));
        when(workspaceScopeFilter.isWorkspaceAllowed(any(Workspace.class))).thenReturn(true);
        when(connectionService.findActiveProviderKind(9)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(connectionService.findActiveGitLabConfig(9)).thenReturn(Optional.of(GITLAB_CONFIG));
        when(connectionService.findActiveBearerToken(9, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(new BearerToken("glpat-nine", null)));
        lenient()
                .when(connectionService.findActiveBearerToken(5, IntegrationKind.GITLAB))
                .thenThrow(unreadable(IntegrationKind.GITLAB));

        Optional<SyncSession> session = provider.getSyncSession(9L, IntegrationKind.GITLAB);

        assertThat(session)
                .get()
                .extracting(SyncSession::scopeId, SyncSession::serverUrl)
                .containsExactly(9L, "https://gitlab.example.com");
        assertThat(session.orElseThrow().syncTargets())
                .extracting(SyncTarget::id, SyncTarget::personalAccessToken, SyncTarget::authMode)
                .containsExactly(tuple(90L, "glpat-nine", AuthMode.PERSONAL_ACCESS_TOKEN));
        verify(workspaceRepository, never()).findAll();
        verify(connectionService, never()).findActiveBearerToken(5, IntegrationKind.GITLAB);
    }

    @Test
    void shouldThrowUnreadableCredentialWhenOwnSessionIsRequested() {
        when(workspaceRepository.findById(5L)).thenReturn(Optional.of(workspace(5)));
        when(workspaceScopeFilter.isWorkspaceAllowed(any(Workspace.class))).thenReturn(true);
        when(connectionService.findActiveProviderKind(5)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(connectionService.findActiveGitLabConfig(5)).thenReturn(Optional.of(GITLAB_CONFIG));
        when(connectionService.findActiveBearerToken(5, IntegrationKind.GITLAB))
                .thenThrow(unreadable(IntegrationKind.GITLAB));

        assertThatThrownBy(() -> provider.getSyncSession(5L, IntegrationKind.GITLAB))
                .isInstanceOf(CredentialUnreadableException.class);
    }

    @Test
    void shouldReturnNoSessionWithoutReadingCredentialsWhenScopeIsNotEligible() {
        Workspace suspended = workspace(2);
        suspended.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        Workspace filtered = workspace(4);
        when(workspaceRepository.findById(1L)).thenReturn(Optional.empty());
        when(workspaceRepository.findById(2L)).thenReturn(Optional.of(suspended));
        when(workspaceRepository.findById(3L)).thenReturn(Optional.of(workspace(3)));
        when(workspaceRepository.findById(4L)).thenReturn(Optional.of(filtered));
        when(connectionService.findActiveProviderKind(3)).thenReturn(Optional.of(IntegrationKind.GITHUB));
        when(connectionService.findActiveProviderKind(4)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(workspaceScopeFilter.isWorkspaceAllowed(filtered)).thenReturn(false);

        assertThat(provider.getSyncSession(1L, IntegrationKind.GITLAB)).isEmpty();
        assertThat(provider.getSyncSession(2L, IntegrationKind.GITLAB)).isEmpty();
        assertThat(provider.getSyncSession(3L, IntegrationKind.GITLAB)).isEmpty();
        assertThat(provider.getSyncSession(4L, IntegrationKind.GITLAB)).isEmpty();
        verify(connectionService, never()).findActiveBearerToken(anyLong(), any());
    }

    @Test
    void shouldThrowUnreadableCredentialWhenTargetsOfThatWorkspaceAreRequested() {
        when(workspaceRepository.findById(5L)).thenReturn(Optional.of(workspace(5)));
        when(connectionService.findActiveGitLabConfig(5)).thenReturn(Optional.of(GITLAB_CONFIG));
        when(connectionService.findActiveBearerToken(5, IntegrationKind.GITLAB))
                .thenThrow(unreadable(IntegrationKind.GITLAB));

        assertThatThrownBy(() -> provider.getSyncTargetsForScope(5L)).isInstanceOf(CredentialUnreadableException.class);
    }
}
