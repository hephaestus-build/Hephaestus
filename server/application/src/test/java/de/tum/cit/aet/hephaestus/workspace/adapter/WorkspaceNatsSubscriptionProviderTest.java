package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.NatsSubscriptionInfo;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.StreamSubscription;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * The provider maps a workspace onto one {@link StreamSubscription} per bound stream. An SCM-connected
 * workspace gets its {@code github}/{@code gitlab} stream; an Outline connection with a registered
 * subscription adds the {@code outline} stream; a Slack connection adds the {@code slack} stream; a
 * workspace with only Outline binds ONLY the outline stream, never an SCM stream.
 */
class WorkspaceNatsSubscriptionProviderTest extends BaseUnitTest {

    private static final long WS = 7L;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceScopeFilter workspaceScopeFilter;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private Workspace workspace;

    private WorkspaceNatsSubscriptionProvider provider;

    @BeforeEach
    void setUp() {
        provider = providerWithFlags(/* outlineEnabled */ true, /* slackEnabled */ true);
        when(workspaceRepository.findById(WS)).thenReturn(Optional.of(workspace));
        when(workspace.getId()).thenReturn(WS);
        lenientReposAndOrg();
    }

    private WorkspaceNatsSubscriptionProvider providerWithFlags(boolean outlineEnabled, boolean slackEnabled) {
        return new WorkspaceNatsSubscriptionProvider(
                workspaceRepository, workspaceScopeFilter, connectionService, outlineEnabled, slackEnabled);
    }

    private void lenientReposAndOrg() {
        org.mockito.Mockito.lenient().when(workspace.getAccountLogin()).thenReturn("acme");
        org.mockito.Mockito.lenient().when(workspace.getRepositoriesToMonitor()).thenReturn(Set.of());
    }

    private StreamSubscription streamNamed(NatsSubscriptionInfo info, String stream) {
        return info.streamSubscriptions().stream()
                .filter(s -> s.streamName().equals(stream))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no subscription for stream " + stream));
    }

    @Test
    void scmConnectedWorkspaceEmitsTheScmStreamWithRepoAndOrgSubjects() {
        RepositoryToMonitor repo = org.mockito.Mockito.mock(RepositoryToMonitor.class);
        when(repo.getNameWithOwner()).thenReturn("acme/app");
        when(workspace.getRepositoriesToMonitor()).thenReturn(Set.of(repo));
        when(workspaceScopeFilter.isRepositoryAllowed("acme/app")).thenReturn(true);
        when(connectionService.findActiveProviderKind(WS)).thenReturn(Optional.of(IntegrationKind.GITHUB));
        when(connectionService.findActiveOutlineConfig(WS)).thenReturn(Optional.empty());

        NatsSubscriptionInfo info = provider.getSubscriptionInfo(WS).orElseThrow();

        assertThat(info.streamSubscriptions()).hasSize(1);
        assertThat(streamNamed(info, "github").subjects()).contains("github.acme.app.>", "github.acme.?.>");
    }

    @Test
    void outlineOnlyWorkspaceEmitsOnlyTheOutlineStream() {
        when(connectionService.findActiveProviderKind(WS)).thenReturn(Optional.empty());
        when(connectionService.findActiveOutlineConfig(WS))
                .thenReturn(
                        Optional.of(new ConnectionConfig.OutlineConfig("https://o.test", "sub-1", "secret", Set.of())));

        NatsSubscriptionInfo info = provider.getSubscriptionInfo(WS).orElseThrow();

        assertThat(info.streamSubscriptions().stream().map(StreamSubscription::streamName))
                .containsExactly("outline");
        assertThat(streamNamed(info, "outline").subjects()).containsExactly("outline.sub-1.>");
    }

    @Test
    void dualConnectionWorkspaceEmitsBothStreams() {
        when(connectionService.findActiveProviderKind(WS)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(connectionService.findActiveOutlineConfig(WS))
                .thenReturn(
                        Optional.of(new ConnectionConfig.OutlineConfig("https://o.test", "sub-9", "secret", Set.of())));

        NatsSubscriptionInfo info = provider.getSubscriptionInfo(WS).orElseThrow();

        assertThat(info.streamSubscriptions().stream().map(StreamSubscription::streamName))
                .containsExactlyInAnyOrder("gitlab", "outline");
    }

    @Test
    void outlineConnectionWithoutRegisteredSubscriptionIsSkipped() {
        when(connectionService.findActiveProviderKind(WS)).thenReturn(Optional.empty());
        when(connectionService.findActiveOutlineConfig(WS))
                .thenReturn(Optional.of(new ConnectionConfig.OutlineConfig("https://o.test", null, null, Set.of())));

        NatsSubscriptionInfo info = provider.getSubscriptionInfo(WS).orElseThrow();

        assertThat(info.hasSubscriptions()).isFalse();
    }

    @Test
    void slackConnectedWorkspaceEmitsAPerTeamSlackFilter() {
        when(connectionService.findActiveProviderKind(WS)).thenReturn(Optional.empty());
        when(connectionService.findActiveOutlineConfig(WS)).thenReturn(Optional.empty());
        when(connectionService.findSlackConfig(WS))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig("T0ABC123", "Acme", null, Set.of())));

        NatsSubscriptionInfo info = provider.getSubscriptionInfo(WS).orElseThrow();

        assertThat(info.streamSubscriptions().stream().map(StreamSubscription::streamName))
                .containsExactly("slack");
        assertThat(streamNamed(info, "slack").subjects()).containsExactly("slack.T0ABC123.>");
    }

    /**
     * The Outline/Slack message handlers are themselves gated on their integration flag, so binding a
     * durable consumer while the flag is off pulls messages that dispatch to nothing. A stale ACTIVE
     * connection row (flag flipped off after a workspace connected) must not resurrect the binding.
     */
    @Test
    void outlineArmIsInertWhenTheOutlineIntegrationIsDisabled() {
        provider = providerWithFlags(/* outlineEnabled */ false, /* slackEnabled */ true);
        when(connectionService.findActiveProviderKind(WS)).thenReturn(Optional.empty());
        when(connectionService.findSlackConfig(WS)).thenReturn(Optional.empty());

        NatsSubscriptionInfo info = provider.getSubscriptionInfo(WS).orElseThrow();

        assertThat(info.hasSubscriptions()).isFalse();
        // The disabled arm must not even ask — no connection lookup on a dead code path.
        org.mockito.Mockito.verify(connectionService, org.mockito.Mockito.never())
                .findActiveOutlineConfig(WS);
    }

    @Test
    void slackArmIsInertWhenTheSlackIntegrationIsDisabled() {
        provider = providerWithFlags(/* outlineEnabled */ true, /* slackEnabled */ false);
        when(connectionService.findActiveProviderKind(WS)).thenReturn(Optional.empty());
        when(connectionService.findActiveOutlineConfig(WS)).thenReturn(Optional.empty());

        NatsSubscriptionInfo info = provider.getSubscriptionInfo(WS).orElseThrow();

        assertThat(info.hasSubscriptions()).isFalse();
        org.mockito.Mockito.verify(connectionService, org.mockito.Mockito.never())
                .findSlackConfig(WS);
    }

    @Test
    void slackConnectionWithoutTeamIdIsSkipped() {
        when(connectionService.findActiveProviderKind(WS)).thenReturn(Optional.empty());
        when(connectionService.findActiveOutlineConfig(WS)).thenReturn(Optional.empty());
        when(connectionService.findSlackConfig(WS))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig(null, "Acme", null, Set.of())));

        NatsSubscriptionInfo info = provider.getSubscriptionInfo(WS).orElseThrow();

        assertThat(info.hasSubscriptions()).isFalse();
    }
}
