package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The changelog that lets one workspace hold a GitHub App installation at a time, applied to connections from before
 * it. Hibernate's schema has no partial indexes, so only the migrated database can prove the index predicate.
 */
@Tag("database")
class GitHubInstallationUniquenessDatabaseTest {

    private static final String INDEX = "uq_connection_one_github_installation";
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("github_installation_uniqueness");

    /** 994001 holds installation 500, 994002 holds 600 and 994003 suspended 700. 994004 once held 800 and left it. */
    private static final String SEED = """
            INSERT INTO workspace (id, account_login, account_type, display_name, slug, status)
            VALUES (994001, 'owner-org', 'ORG', 'Owner', 'owner-org', 'ACTIVE'),
                   (994002, 'first-org', 'ORG', 'First', 'first-org', 'ACTIVE'),
                   (994003, 'other-org', 'ORG', 'Other', 'other-org', 'ACTIVE'),
                   (994004, 'second-org', 'ORG', 'Second', 'second-org', 'ACTIVE');
            INSERT INTO connection (id, workspace_id, kind, instance_key, state, config)
            VALUES (994101, 994001, 'GITHUB', '500', 'ACTIVE', '{"type":"GITHUB_APP","installationId":500,"enabledStreams":[]}'),
                   (994102, 994002, 'GITHUB', '600', 'ACTIVE', '{"type":"GITHUB_APP","installationId":600,"enabledStreams":[]}'),
                   (994103, 994003, 'GITHUB', '700', 'SUSPENDED', '{"type":"GITHUB_APP","installationId":700,"enabledStreams":[]}'),
                   (994104, 994004, 'GITHUB', '800', 'UNINSTALLED', '{"type":"GITHUB_APP","installationId":800,"enabledStreams":[]}');
            """;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        PostgreSQLTestContainer.migrateDatabase(DATABASE, "prod");
        try (Connection connection = connect(DATABASE);
                var statement = connection.createStatement()) {
            statement.execute(SEED);
        }
    }

    @Test
    void shouldRefuseASecondWorkspaceForAnInstallationThatIsHeld() {
        assertThatThrownBy(() -> inRolledBackTransaction(workspace(994201) + gitHubApp(994201, "500", "ACTIVE")))
                .hasMessageContaining(INDEX);
    }

    @Test
    void shouldRefuseASecondWorkspaceForAnInstallationWhoseHolderSuspendedIt() {
        assertThatThrownBy(() -> inRolledBackTransaction(workspace(994201) + gitHubApp(994201, "700", "PENDING")))
                .hasMessageContaining(INDEX);
    }

    @Test
    void shouldAllowAnInstallationToBeHeldAgainOnceItsHolderDisconnected() {
        assertThatCode(() -> inRolledBackTransaction(workspace(994201)
                        + gitHubApp(994201, "800", "ACTIVE")
                        + workspace(994202)
                        + gitHubApp(994202, "600", "UNINSTALLED")))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldLeaveTokenConnectionsOutOfTheIndex() {
        String pat = "', 'ACTIVE', '{\"type\":\"GITHUB_PAT\",\"enabledStreams\":[]}');";
        assertThatCode(() -> inRolledBackTransaction(workspace(994201)
                        + workspace(994202)
                        + "INSERT INTO connection (workspace_id, kind, instance_key, state, config)"
                        + " VALUES (994201, 'GITHUB', 'pat" + pat
                        + "INSERT INTO connection (workspace_id, kind, instance_key, state, config)"
                        + " VALUES (994202, 'GITHUB', 'pat" + pat))
                .doesNotThrowAnyException();
    }

    /** Runs {@code sql} in a transaction it rolls back, so no test sees the rows of another. */
    private static void inRolledBackTransaction(String sql) throws SQLException {
        try (Connection connection = connect(DATABASE)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            } finally {
                connection.rollback();
            }
        }
    }

    private static String workspace(long id) {
        return "INSERT INTO workspace (id, account_login, account_type, display_name, slug, status)" + " VALUES (" + id
                + ", 'org-" + id + "', 'ORG', 'Org', 'org-" + id + "', 'ACTIVE');";
    }

    private static String gitHubApp(long workspaceId, String installationId, String state) {
        return "INSERT INTO connection (workspace_id, kind, instance_key, state, config) VALUES (" + workspaceId
                + ", 'GITHUB', '" + installationId + "', '" + state + "', '{\"type\":\"GITHUB_APP\",\"installationId\":"
                + installationId + ",\"enabledStreams\":[]}');";
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }
}
