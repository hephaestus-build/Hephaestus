package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The test schema is Hibernate-generated, so the one-active-Slack-per-team index is absent here. */
class SlackWorkspaceResolverIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionRepository connectionRepository;

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
    void shouldResolveNothingWhenTeamHasTwoActiveConnections() {
        String team = uniqueTeam();
        connectSlack(newWorkspace(), team, IntegrationState.ACTIVE);
        connectSlack(newWorkspace(), team, IntegrationState.ACTIVE);

        assertThat(resolver().resolveWorkspaceId(team)).isEmpty();
    }

    private SlackWorkspaceResolver resolver() {
        return new SlackWorkspaceResolver(jdbcTemplate);
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
                workspace,
                IntegrationKind.SLACK,
                team,
                new ConnectionConfig.SlackConfig(team, null, null, null, null, Set.of()));
        connection.setState(state);
        connectionRepository.save(connection);
    }
}
