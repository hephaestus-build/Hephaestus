package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;

/** A delivery reaches the workspaces whose subscription its subject matches, and no other. */
class WorkspaceEventSubscriptionsTest extends BaseUnitTest {

    private static final long WS = 7L;

    @Mock
    private WorkspaceRepository workspaces;

    @Mock
    private RepositoryToMonitorRepository monitors;

    @Mock
    private WorkspaceScopeFilter scope;

    @Mock
    private ConnectionService connections;

    @Mock
    private Workspace workspace;

    private WorkspaceEventSubscriptions subscriptions;

    @BeforeEach
    void setUp() {
        subscriptions = new WorkspaceEventSubscriptions(workspaces, monitors, scope, connections, true, true, true);
        lenient().when(workspaces.findById(WS)).thenReturn(Optional.of(workspace));
        lenient().when(workspace.getId()).thenReturn(WS);
        lenient().when(workspace.getStatus()).thenReturn(Workspace.WorkspaceStatus.ACTIVE);
        lenient().when(workspace.getAccountLogin()).thenReturn("acme");
        lenient().when(workspace.getRepositoriesToMonitor()).thenReturn(Set.of());
        lenient().when(scope.isWorkspaceAllowed(workspace)).thenReturn(true);
    }

    private void monitorsGitHubRepository() {
        RepositoryToMonitor monitor = mock(RepositoryToMonitor.class);
        lenient().when(monitor.getNameWithOwner()).thenReturn("acme/app");
        lenient().when(monitor.getWorkspace()).thenReturn(workspace);
        lenient().when(workspace.getRepositoriesToMonitor()).thenReturn(Set.of(monitor));
        lenient().when(scope.isRepositoryAllowed("acme/app")).thenReturn(true);
        lenient().when(monitors.findAllWithWorkspaceByNameWithOwner("acme/app")).thenReturn(List.of(monitor));
        lenient().when(connections.findActiveProviderKind(WS)).thenReturn(Optional.of(IntegrationKind.GITHUB));
    }

    @Test
    void shouldReachTheWorkspaceThatMonitorsTheRepository() {
        monitorsGitHubRepository();

        assertThat(subscriptions.subscribers("github.acme.app.issues")).containsExactly(WS);
        assertThat(subscriptions.subscribers("github.acme.other.issues")).isEmpty();
    }

    @Test
    void shouldReachTheWorkspaceOfTheOrganizationForAnOrganizationEvent() {
        monitorsGitHubRepository();
        when(workspaces.findAllByAccountLoginIgnoreCase("acme")).thenReturn(List.of(workspace));

        assertThat(subscriptions.subscribers("github.acme.?.organization")).containsExactly(WS);
    }

    @Test
    void shouldReachNoWorkspaceWithAnInstallationEvent() {
        assertThat(subscriptions.subscribers("github.?.?.installation")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = Workspace.WorkspaceStatus.class,
            names = {"SUSPENDED", "PURGED"})
    void shouldNotReachAnInactiveWorkspace(Workspace.WorkspaceStatus status) {
        monitorsGitHubRepository();
        when(workspace.getStatus()).thenReturn(status);

        assertThat(subscriptions.subscribers("github.acme.app.issues")).isEmpty();
    }

    @Test
    void shouldNotReachAWorkspaceOutsideTheMonitoringFilter() {
        monitorsGitHubRepository();
        when(scope.isWorkspaceAllowed(workspace)).thenReturn(false);

        assertThat(subscriptions.subscribers("github.acme.app.issues")).isEmpty();
    }

    @Test
    void shouldReachTheWorkspaceOfTheGitLabConnectionAlone() {
        Connection connection = mock(Connection.class);
        when(connection.getId()).thenReturn(42L);
        when(connections.findWorkspaceIdsWithConnection(IntegrationKind.GITLAB, IntegrationState.ACTIVE))
                .thenReturn(List.of(WS));
        when(connections.findActiveProviderKind(WS)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(connections.findActive(WS, IntegrationKind.GITLAB)).thenReturn(Optional.of(connection));

        assertThat(subscriptions.subscribers("gitlab.?connection.42.push")).containsExactly(WS);
        assertThat(subscriptions.subscribers("gitlab.?connection.43.push")).isEmpty();
    }

    @Test
    void shouldNotReachAGitLabWorkspaceWhenTheAdapterIsDisabled() {
        subscriptions = new WorkspaceEventSubscriptions(workspaces, monitors, scope, connections, true, true, false);
        when(connections.findWorkspaceIdsWithConnection(IntegrationKind.GITLAB, IntegrationState.ACTIVE))
                .thenReturn(List.of(WS));
        when(connections.findActiveProviderKind(WS)).thenReturn(Optional.of(IntegrationKind.GITLAB));

        assertThat(subscriptions.subscribers("gitlab.?connection.42.push")).isEmpty();
    }

    @Test
    void shouldReachTheWorkspaceOfTheOutlineSubscription() {
        when(connections.findWorkspaceIdsWithConnection(IntegrationKind.OUTLINE, IntegrationState.ACTIVE))
                .thenReturn(List.of(WS));
        when(connections.findActiveOutlineConfig(WS))
                .thenReturn(
                        Optional.of(new ConnectionConfig.OutlineConfig("https://o.test", "sub-1", "secret", Set.of())));

        assertThat(subscriptions.subscribers("outline.sub-1.documents~update")).containsExactly(WS);
        assertThat(subscriptions.subscribers("outline.sub-2.documents~update")).isEmpty();
    }

    @Test
    void shouldReachTheWorkspaceOfTheSlackTeam() {
        when(connections.findWorkspaceIdsWithConnection(IntegrationKind.SLACK, IntegrationState.ACTIVE))
                .thenReturn(List.of(WS));
        when(connections.findSlackConfig(WS))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig("T1", null, null, Set.of())));

        assertThat(subscriptions.subscribers("slack.T1.C1.message")).containsExactly(WS);
        assertThat(subscriptions.subscribers("slack.T2.C1.message")).isEmpty();
    }
}
