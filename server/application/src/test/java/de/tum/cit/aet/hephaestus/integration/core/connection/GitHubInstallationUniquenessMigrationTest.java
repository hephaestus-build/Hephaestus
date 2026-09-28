package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The changelog that lets one workspace hold a GitHub App installation at a time, applied to connections from before
 * it. Hibernate's schema has no partial indexes, so only the migrated database can prove the index predicate.
 */
@Tag("database")
class GitHubInstallationUniquenessMigrationTest {

    private static final String CHANGELOG = "1790610365032_changelog.xml";
    private static final String INDEX = "uq_connection_one_github_installation";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("github_installation_uniqueness_migration");

    /**
     * Installation 500 is held by its owner (994001) and was bound without proof to another workspace (994002).
     * Installation 600 was bound twice with a recorded installation, first by 994003. Installation 700 is suspended by
     * its owner. 994004 once held installation 800 and disconnected it, and 994005 once ran on a PAT.
     */
    private static final String SEED = """
            INSERT INTO workspace (id, account_login, account_type, display_name, is_publicly_viewable, slug, status)
            VALUES (994001, 'owner-org', 'ORG', 'Owner', false, 'owner-org', 'ACTIVE'),
                   (994002, 'other-org', 'ORG', 'Other', false, 'other-org', 'ACTIVE'),
                   (994003, 'first-org', 'ORG', 'First', false, 'first-org', 'ACTIVE'),
                   (994004, 'second-org', 'ORG', 'Second', false, 'second-org', 'ACTIVE'),
                   (994005, 'pat-org', 'ORG', 'Pat', false, 'pat-org', 'ACTIVE');
            INSERT INTO connection (id, workspace_id, kind, instance_key, state, config, credentials_encrypted, credentials_alg)
            VALUES (994101, 994001, 'GITHUB', '500', 'ACTIVE',
                    '{"type":"GITHUB_APP","installationId":500,"orgLogin":"owner-org","enabledStreams":[]}', NULL, NULL),
                   (994102, 994002, 'GITHUB', '500', 'ACTIVE',
                    '{"type":"GITHUB_APP","installationId":null,"enabledStreams":[]}', '\\x01'::bytea, 'aesgcm-v2'),
                   (994103, 994003, 'GITHUB', '600', 'ACTIVE',
                    '{"type":"GITHUB_APP","installationId":600,"enabledStreams":[]}', NULL, NULL),
                   (994104, 994004, 'GITHUB', '600', 'ACTIVE',
                    '{"type":"GITHUB_APP","installationId":600,"enabledStreams":[]}', NULL, NULL),
                   (994105, 994005, 'GITHUB', '700', 'SUSPENDED',
                    '{"type":"GITHUB_APP","installationId":700,"enabledStreams":[]}', NULL, NULL),
                   (994107, 994004, 'GITHUB', '800', 'UNINSTALLED',
                    '{"type":"GITHUB_APP","installationId":800,"enabledStreams":[]}', NULL, NULL),
                   (994108, 994005, 'GITHUB', 'pat', 'UNINSTALLED',
                    '{"type":"GITHUB_PAT","enabledStreams":[]}', NULL, NULL);
            """;

    @BeforeAll
    static void migrateAcrossTheChangelog() throws Exception {
        try (Connection connection = connect(DATABASE);
                Liquibase liquibase = liquibase(connection)) {
            int changeSets = migrateToTheChangelog(liquibase);
            try (var statement = connection.createStatement()) {
                statement.execute(SEED);
            }
            connection.commit();
            liquibase.update(changeSets, CONTEXTS, LABELS);
        }
    }

    @Test
    void shouldRetireAConnectionThatNeverRecordedItsInstallationAndKeepTheOwners() throws SQLException {
        assertThat(column("SELECT id::text, state FROM connection WHERE id IN (994101, 994102)"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("994101", "ACTIVE", "994102", "UNINSTALLED"));
        assertThat(column("""
                SELECT state_reason, concat_ws(',', coalesce(credentials_encrypted::text, 'no credentials'),
                    coalesce(credentials_alg, 'no algorithm'))
                FROM connection WHERE id = 994102
                """))
                .containsExactlyEntriesOf(
                        Map.of("GitHub App installation was never verified", "no credentials,no algorithm"));
        assertThat(column("""
                SELECT event_type, concat_ws('>', from_state, to_state) FROM connection_audit
                WHERE connection_id = 994102
                """)).containsExactlyEntriesOf(Map.of("UNVERIFIED_INSTALLATION", "ACTIVE>UNINSTALLED"));
    }

    @Test
    void shouldKeepTheFirstWorkspaceToHoldAnInstallationThatTwoHeld() throws SQLException {
        assertThat(column("SELECT id::text, state FROM connection WHERE id IN (994103, 994104)"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("994103", "ACTIVE", "994104", "UNINSTALLED"));
        assertThat(column("SELECT event_type, state_reason FROM connection_audit a JOIN connection c"
                        + " ON c.id = a.connection_id WHERE a.connection_id = 994104"))
                .containsExactlyEntriesOf(
                        Map.of("SUPERSEDED_INSTALLATION", "GitHub App installation is held by an earlier workspace"));
    }

    @Test
    void shouldLeaveSuspendedAndDisconnectedConnectionsAsTheyWere() throws SQLException {
        assertThat(column("SELECT id::text, state FROM connection WHERE id IN (994105, 994107, 994108)"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("994105", "SUSPENDED", "994107", "UNINSTALLED", "994108", "UNINSTALLED"));
        assertThat(column("SELECT connection_id::text, event_type FROM connection_audit"
                        + " WHERE connection_id IN (994101, 994103, 994105, 994107, 994108)"))
                .isEmpty();
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

    @Test
    void shouldDropTheIndexAndKeepTheRetiredConnectionsWhenTheChangelogRollsBack() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("github_installation_uniqueness_rollback");
        try (Connection connection = connect(database);
                Liquibase liquibase = liquibase(connection)) {
            int changeSets = migrateToTheChangelog(liquibase);
            try (var statement = connection.createStatement()) {
                statement.execute(SEED);
            }
            connection.commit();
            liquibase.update(changeSets, CONTEXTS, LABELS);

            liquibase.rollback(changeSets, CONTEXTS, LABELS);

            assertThat(columns(
                            connection, "SELECT 'index', coalesce(to_regclass('public." + INDEX + "')::text, 'gone')"))
                    .containsExactlyEntriesOf(Map.of("index", "gone"));
            assertThat(columns(connection, "SELECT id::text, state FROM connection WHERE id IN (994102, 994104)"))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("994102", "UNINSTALLED", "994104", "UNINSTALLED"));
        }
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
        return "INSERT INTO workspace (id, account_login, account_type, display_name, is_publicly_viewable, slug, status)"
                + " VALUES (" + id + ", 'org-" + id + "', 'ORG', 'Org', false, 'org-" + id + "', 'ACTIVE');";
    }

    private static String gitHubApp(long workspaceId, String installationId, String state) {
        return "INSERT INTO connection (workspace_id, kind, instance_key, state, config) VALUES (" + workspaceId
                + ", 'GITHUB', '" + installationId + "', '" + state + "', '{\"type\":\"GITHUB_APP\",\"installationId\":"
                + installationId + ",\"enabledStreams\":[]}');";
    }

    /** Applies the chain up to the changelog under test and returns how many changesets the changelog holds. */
    private static int migrateToTheChangelog(Liquibase liquibase) throws Exception {
        var pending = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
        int before = IntStream.range(0, pending.size())
                .filter(index -> pending.get(index).getFilePath().endsWith(CHANGELOG))
                .findFirst()
                .orElseThrow();
        liquibase.update(before, CONTEXTS, LABELS);
        return (int) pending.stream()
                .filter(changeSet -> changeSet.getFilePath().endsWith(CHANGELOG))
                .count();
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        return new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database);
    }

    private static Map<String, String> column(String query) throws SQLException {
        try (Connection connection = connect(DATABASE)) {
            return columns(connection, query);
        }
    }

    private static Map<String, String> columns(Connection connection, String query) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.put(rows.getString(1), rows.getString(2));
            }
        }
        return values;
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }
}
