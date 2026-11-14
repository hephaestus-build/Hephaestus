package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class SlackWorkspaceResolverIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private ActiveConnectionGuard activeConnections;

    @Test
    void shouldResolveNothingWhenTeamHasNoActiveConnection() {
        String team = uniqueTeam();
        connectSlack(newWorkspace(), team, IntegrationState.UNINSTALLED);

        assertThat(resolver().resolveWorkspaceId(team)).isEmpty();
    }

    @Test
    void shouldResolveOwnerWhenTeamHasOneActiveConnection() {
        String team = uniqueTeam();
        Workspace owner = newWorkspace();
        connectSlack(owner, team, IntegrationState.ACTIVE);
        connectSlack(newWorkspace(), team, IntegrationState.UNINSTALLED);

        assertThat(resolver().resolveWorkspaceId(team)).contains(owner.getId());
    }

    @Test
    void shouldPreserveOwnerWhenAnotherWorkspaceTriesToActivateTheSameTeam() {
        String team = uniqueTeam();
        Workspace owner = newWorkspace();
        Workspace otherWorkspace = newWorkspace();
        connectSlack(owner, team, IntegrationState.ACTIVE);

        assertThatThrownBy(() -> connectSlack(otherWorkspace, team, IntegrationState.ACTIVE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_connection_one_active_slack_per_team");
        assertThat(resolver().resolveWorkspaceId(team)).contains(owner.getId());
    }

    private SlackWorkspaceResolver resolver() {
        return new SlackWorkspaceResolver(connections, activeConnections);
    }

    private static String uniqueTeam() {
        return "T" + System.nanoTime();
    }

    private Workspace newWorkspace() {
        String slug = "slack-resolver-" + System.nanoTime();
        return createWorkspace(slug, "Slack Resolver", slug, AccountType.ORG, persistUser(slug + "-owner"));
    }

    private void connectSlack(Workspace workspace, String team, IntegrationState state) {
        Connection connection = new Connection(
                workspace, IntegrationKind.SLACK, team, new ConnectionConfig.SlackConfig(team, null, null, Set.of()));
        ConnectionFixtures.reachState(connection, state);
        connectionRepository.save(connection);
    }
}
