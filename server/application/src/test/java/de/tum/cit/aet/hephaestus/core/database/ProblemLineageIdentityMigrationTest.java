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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lineage rows recorded before the identity column keep their comparison when their problem has a key: its identity is
 * the key. A row of a problem without a key goes, because its identity is a hash of the server's serialization.
 */
@Tag("database")
class ProblemLineageIdentityMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("problem_lineage_identity");
    private static final String CHANGELOG = "db/changelog/next/20261121211145-feat-problem-lineage-resolves.xml";

    @Test
    void shouldDeriveTheIdentityOfAKeyedProblemAndDropTheRowsOfAProblemWithoutAKey() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            // Only the columns and constraints that the change sets read or replace.
            statement.execute("""
                    CREATE TABLE problem (id uuid PRIMARY KEY, workspace_id bigint, key varchar(64));
                    CREATE TABLE problem_lineage (
                        id uuid PRIMARY KEY, workspace_id bigint, problem_id uuid, prior_problem_id uuid,
                        comparison varchar(16));
                    CREATE TABLE feedback_copy (id uuid, workspace_id bigint, UNIQUE (id, workspace_id));
                    CREATE TABLE agent_action (kind varchar(32), CONSTRAINT ck_agent_action_kind CHECK (true));
                    INSERT INTO problem VALUES
                        ('00000000-0000-0000-0000-00000000000a', 1, 'missing-tests'),
                        ('00000000-0000-0000-0000-00000000000b', 1, 'swallowed-error'),
                        ('00000000-0000-0000-0000-00000000000c', 1, NULL);
                    INSERT INTO problem_lineage VALUES
                        ('00000000-0000-0000-0000-000000000001', 1, '00000000-0000-0000-0000-00000000000a', NULL, 'NEW'),
                        ('00000000-0000-0000-0000-000000000002', 1, NULL, '00000000-0000-0000-0000-00000000000b',
                          'ABSENT'),
                        ('00000000-0000-0000-0000-000000000003', 1, '00000000-0000-0000-0000-00000000000c', NULL,
                          'NEW');
                    """);
            connection.commit();

            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts("prod"), new LabelExpression());

                assertThat(identities(connection))
                        .containsExactly(
                                Map.entry("00000000-0000-0000-0000-000000000001", "key:missing-tests"),
                                Map.entry("00000000-0000-0000-0000-000000000002", "key:swallowed-error"));
            }
        }
    }

    private static Map<String, String> identities(Connection connection) throws SQLException {
        var identities = new LinkedHashMap<String, String>();
        try (var select = connection.prepareStatement("SELECT id, identity FROM problem_lineage ORDER BY id");
                var rows = select.executeQuery()) {
            while (rows.next()) identities.put(rows.getString(1), rows.getString(2));
        }
        return identities;
    }
}
