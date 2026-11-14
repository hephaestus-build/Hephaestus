package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries.ConnectionView;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderEventSubscriptions.Subscription;
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
    private ConnectionQueries connections;

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
        lenient().when(monitors.findWorkspaceIdsByNameWithOwner("acme/app")).thenReturn(List.of(WS));
        connection(
                IntegrationKind.GITHUB,
                new ConnectionConfig.GitHubAppConfig(1L, "acme", "https://github.com", Set.of()));
    }

    @Test
    void shouldReachTheWorkspaceThatMonitorsTheRepository() {
        monitorsGitHubRepository();

        assertThat(subscriptions.subscribers("github.acme.app.issues")).containsExactly(new Subscription(WS, 42L));
        assertThat(subscriptions.subscribers("github.acme.other.issues")).isEmpty();
    }

    @Test
    void shouldReachTheWorkspaceOfTheOrganizationForAnOrganizationEvent() {
        monitorsGitHubRepository();
        when(workspaces.findAllByAccountLoginIgnoreCase("acme")).thenReturn(List.of(workspace));

        assertThat(subscriptions.subscribers("github.acme.?.organization")).containsExactly(new Subscription(WS, 42L));
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
        connection(
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig("https://gitlab.test", 1L, null, Set.of(), null));

        assertThat(subscriptions.subscribers("gitlab.?connection.42.push")).containsExactly(new Subscription(WS, 42L));
        assertThat(subscriptions.subscribers("gitlab.?connection.43.push")).isEmpty();
    }

    @Test
    void shouldNotReachAGitLabWorkspaceWhenTheAdapterIsDisabled() {
        subscriptions = new WorkspaceEventSubscriptions(workspaces, monitors, scope, connections, true, true, false);

        assertThat(subscriptions.subscribers("gitlab.?connection.42.push")).isEmpty();
    }

    @Test
    void shouldReachTheWorkspaceOfTheOutlineSubscription() {
        connection(
                IntegrationKind.OUTLINE,
                new ConnectionConfig.OutlineConfig("https://o.test", "sub-1", "route", Set.of()));

        assertThat(subscriptions.subscribers("outline.sub-1.documents~update"))
                .containsExactly(new Subscription(WS, 42L));
        assertThat(subscriptions.subscribers("outline.sub-2.documents~update")).isEmpty();
    }

    @Test
    void shouldReachTheWorkspaceOfTheSlackTeam() {
        connection(IntegrationKind.SLACK, new ConnectionConfig.SlackConfig("T1", null, null, Set.of()));

        assertThat(subscriptions.subscribers("slack.T1.C1.message")).containsExactly(new Subscription(WS, 42L));
        assertThat(subscriptions.subscribers("slack.T2.C1.message")).isEmpty();
    }

    private void connection(IntegrationKind kind, ConnectionConfig config) {
        var connection = new ConnectionView(42L, kind, IntegrationState.ACTIVE, config, null, null);
        lenient().when(connections.findByWorkspaceId(WS)).thenReturn(List.of(connection));
        for (IntegrationState state : IntegrationState.values()) {
            lenient()
                    .when(connections.findWorkspaceIdsByKindAndState(kind, state))
                    .thenReturn(List.of(WS));
        }
    }
}
