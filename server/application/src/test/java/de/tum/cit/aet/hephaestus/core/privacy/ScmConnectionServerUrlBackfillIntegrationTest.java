package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Set;
import javax.sql.DataSource;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ScmConnectionServerUrlBackfillIntegrationTest extends BaseIntegrationTest {
    private static final String CONFIGURED = "https://gitlab.corp.example";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    private long connection(String slug, IntegrationKind kind, ConnectionConfig config) {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(slug));
        return connections
                .saveAndFlush(new Connection(workspace, kind, slug, config))
                .getId();
    }

    private void forgetUrl(long id) {
        jdbc.update("UPDATE connection SET config = config - 'serverUrl' WHERE id = ?", id);
    }

    private @Nullable String urlOf(long id) {
        return jdbc.queryForObject("SELECT config ->> 'serverUrl' FROM connection WHERE id = ?", String.class, id);
    }

    private void backfill() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(connection));
            new ScmConnectionServerUrlBackfillChange(CONFIGURED + "/").execute(database);
            // Liquibase turns autocommit off, and it commits a change set itself.
            connection.commit();
        }
    }

    @Test
    void shouldStoreTheInstanceThatEachConnectionAlreadyUsedAndRegisterIt() throws Exception {
        databaseTestUtils.cleanDatabase();
        long gitLab = connection(
                "backfill-gitlab",
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig(CONFIGURED, 1L, null, Set.of(), null));
        long gitHub = connection(
                "backfill-github",
                IntegrationKind.GITHUB,
                new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        long stated = connection(
                "backfill-stated",
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig("https://gitlab.other.example", 2L, null, Set.of(), null));
        long outline = connection(
                "backfill-outline",
                IntegrationKind.OUTLINE,
                new ConnectionConfig.OutlineConfig(null, null, null, Set.of()));
        forgetUrl(gitLab);
        forgetUrl(gitHub);
        jdbc.update("DELETE FROM identity_provider WHERE type = 'GITLAB' AND server_url = ?", CONFIGURED);

        backfill();
        backfill();

        assertThat(urlOf(gitLab)).isEqualTo(CONFIGURED);
        assertThat(urlOf(gitHub)).isEqualTo("https://github.com");
        assertThat(urlOf(stated)).isEqualTo("https://gitlab.other.example");
        assertThat(urlOf(outline)).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM identity_provider WHERE type = 'GITLAB' AND server_url = ?",
                        Long.class,
                        CONFIGURED))
                .isEqualTo(1L);
        assertThat(connections.findById(gitLab).orElseThrow().getConfig())
                .isInstanceOfSatisfying(
                        ConnectionConfig.GitLabConfig.class,
                        config -> assertThat(config.serverUrl()).isEqualTo(CONFIGURED));
    }

    @Test
    void shouldGiveEverySourceControlConfigOneInstance() {
        assertThat(new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()).serverUrl())
                .isEqualTo("https://github.com");
        assertThat(new ConnectionConfig.GitHubAppConfig(1L, "acme", null, Set.of()).serverUrl())
                .isEqualTo("https://github.com");
        assertThatThrownBy(() -> new ConnectionConfig.GitLabConfig(" ", 1L, null, Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
