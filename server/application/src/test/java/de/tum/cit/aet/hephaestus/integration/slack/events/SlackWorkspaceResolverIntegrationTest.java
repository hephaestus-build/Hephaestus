package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveConnectionGuard;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * A signed Slack request names its workspace only through the one active connection of its team. A team that no
 * active connection holds names none, and the database refuses a second active connection of a team, so no team can
 * name two workspaces.
 */
class SlackWorkspaceResolverIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ConnectionRepository connections;

    private SlackWorkspaceResolver resolver;

    @BeforeEach
    void createResolver() {
        resolver = new SlackWorkspaceResolver(connectionService, mock(ActiveConnectionGuard.class));
    }

    @Test
    void shouldNameTheWorkspaceOfTheTeamsActiveConnection() {
        Workspace workspace = workspace();
        connect(workspace, "T-BOUND", IntegrationState.ACTIVE);

        assertThat(resolver.resolveWorkspaceId("T-BOUND")).contains(workspace.getId());
    }

    @Test
    void shouldNameNoWorkspaceForATeamThatNoActiveConnectionHolds() {
        connect(workspace(), "T-ENDED", IntegrationState.UNINSTALLED);

        assertThat(resolver.resolveWorkspaceId("T-UNBOUND")).isEmpty();
        assertThat(resolver.resolveWorkspaceId("T-ENDED")).isEmpty();
        assertThat(resolver.resolveWorkspaceId("")).isEmpty();
    }

    @Test
    void shouldRefuseASecondActiveConnectionOfTheTeamSoNoTeamNamesTwoWorkspaces() {
        Workspace first = workspace();
        connect(first, "T-SHARED", IntegrationState.ACTIVE);

        assertThatThrownBy(() -> connect(workspace(), "T-SHARED", IntegrationState.ACTIVE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_connection_one_active_slack_per_team");
        assertThat(resolver.resolveWorkspaceId("T-SHARED")).contains(first.getId());
    }

    @Test
    void shouldNameOnlyTheActiveWorkspaceWhenTheTeamWasConnectedElsewhereBefore() {
        Workspace before = workspace();
        connect(before, "T-MOVED", IntegrationState.UNINSTALLED);
        Workspace now = workspace();
        connect(now, "T-MOVED", IntegrationState.ACTIVE);

        assertThat(resolver.resolveWorkspaceId("T-MOVED")).contains(now.getId());
    }

    private Workspace workspace() {
        String slug = "slack-team-" + System.nanoTime();
        return createWorkspace(slug, "Slack team", slug, AccountType.ORG, persistUser(slug + "-owner"));
    }

    private void connect(Workspace workspace, String team, IntegrationState state) {
        Connection connection = new Connection(
                workspace, IntegrationKind.SLACK, team, new ConnectionConfig.SlackConfig(team, null, null, Set.of()));
        ConnectionFixtures.reachState(connection, state);
        connections.saveAndFlush(connection);
    }
}
