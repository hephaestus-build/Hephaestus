package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The problem takes the identity that lineage rows held: from a row that names it as the newer problem or as the
 * previous one, else from its key. A problem without a key that no row names has none. The lineage copy goes, and its
 * rollback copies it back from the problem.
 */
@Tag("database")
class ProblemIdentityMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("problem_identity");
    private static final String CHANGELOG = "db/changelog/next/20261122064614-feat-repeats-by-problem-identity.xml";
    private static final String NEW = "scheme-1:" + "a".repeat(64);
    private static final String ABSENT = "scheme-1:" + "b".repeat(64);
    private static final String UNCHANGED = "scheme-1:" + "c".repeat(64);

    @Test
    void shouldMoveTheIdentityFromLineageToTheProblemAndBackOnRollback() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            // Only the columns and objects that the change sets read or replace.
            statement.execute("""
                    CREATE TABLE problem (id uuid PRIMARY KEY, workspace_id bigint, key varchar(64));
                    CREATE TABLE problem_lineage (
                        id uuid PRIMARY KEY, workspace_id bigint, problem_id uuid, prior_problem_id uuid,
                        comparison varchar(16), identity text NOT NULL);
                    CREATE INDEX idx_problem_lineage_identity ON problem_lineage (workspace_id, identity);
                    INSERT INTO problem VALUES
                        ('00000000-0000-0000-0000-00000000000a', 1, NULL),
                        ('00000000-0000-0000-0000-00000000000b', 1, NULL),
                        ('00000000-0000-0000-0000-00000000000c', 1, NULL),
                        ('00000000-0000-0000-0000-00000000000d', 1, NULL),
                        ('00000000-0000-0000-0000-00000000000e', 1, 'missing-tests'),
                        ('00000000-0000-0000-0000-00000000000f', 1, NULL);
                    """);
            statement.execute("INSERT INTO problem_lineage VALUES"
                    + " ('00000000-0000-0000-0000-000000000001', 1, '00000000-0000-0000-0000-00000000000a', NULL,"
                    + " 'NEW', '" + NEW + "'),"
                    + " ('00000000-0000-0000-0000-000000000002', 1, NULL, '00000000-0000-0000-0000-00000000000b',"
                    + " 'ABSENT', '" + ABSENT + "'),"
                    + " ('00000000-0000-0000-0000-000000000003', 1, '00000000-0000-0000-0000-00000000000c',"
                    + " '00000000-0000-0000-0000-00000000000d', 'UNCHANGED', '" + UNCHANGED + "')");
            connection.commit();

            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                var contexts = new Contexts("prod");
                var labels = new LabelExpression();
                liquibase.update(contexts, labels);

                var expected = new LinkedHashMap<String, @Nullable String>();
                expected.put("00000000-0000-0000-0000-00000000000a", NEW);
                expected.put("00000000-0000-0000-0000-00000000000b", ABSENT);
                expected.put("00000000-0000-0000-0000-00000000000c", UNCHANGED);
                expected.put("00000000-0000-0000-0000-00000000000d", UNCHANGED);
                expected.put("00000000-0000-0000-0000-00000000000e", "key:missing-tests");
                expected.put("00000000-0000-0000-0000-00000000000f", null);
                assertThat(identities(connection, "SELECT id, identity FROM problem ORDER BY id"))
                        .containsExactlyEntriesOf(expected);
                assertThat(lineageHasIdentity(connection)).isFalse();

                liquibase.rollback(1, contexts, labels);

                assertThat(identities(connection, "SELECT id, identity FROM problem_lineage ORDER BY id"))
                        .containsExactly(
                                Map.entry("00000000-0000-0000-0000-000000000001", NEW),
                                Map.entry("00000000-0000-0000-0000-000000000002", ABSENT),
                                Map.entry("00000000-0000-0000-0000-000000000003", UNCHANGED));
            }
        }
    }

    private static boolean lineageHasIdentity(Connection connection) throws SQLException {
        try (var select = connection.prepareStatement("""
                        SELECT EXISTS (SELECT 1 FROM information_schema.columns
                          WHERE table_name = 'problem_lineage' AND column_name = 'identity')
                        """);
                var rows = select.executeQuery()) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static Map<String, @Nullable String> identities(Connection connection, String query) throws SQLException {
        var identities = new LinkedHashMap<String, @Nullable String>();
        try (var select = connection.prepareStatement(query);
                var rows = select.executeQuery()) {
            while (rows.next()) identities.put(rows.getString(1), rows.getString(2));
        }
        return identities;
    }
}
