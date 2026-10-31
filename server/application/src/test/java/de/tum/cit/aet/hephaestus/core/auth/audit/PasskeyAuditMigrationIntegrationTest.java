package de.tum.cit.aet.hephaestus.core.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class PasskeyAuditMigrationIntegrationTest {

    // The changeset that replaces ck_auth_event_event_type with the list that admits passkey events.
    private static final String CONSTRAINT_CHANGESET = "1792515903984-16";
    private static final String CHECK_VIOLATION = "23514";

    @Test
    void shouldAdmitPasskeyEventsAndKeepEarlierEventsWhenTheEventTypeConstraintIsWidened() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_passkey_audit_migration");
        migrate(database, false);
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE auth_event_passkey_test PARTITION OF auth_event "
                    + "FOR VALUES FROM ('2000-01-01') TO ('2000-02-01')");
            insert(statement, "PUBLIC_ACTIVITY_CHANGED");
            assertThatThrownBy(() -> insert(statement, "PASSKEY_VERIFIED"))
                    .isInstanceOfSatisfying(
                            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION));
        }

        migrate(database, true);

        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            assertThat(count(statement, "PUBLIC_ACTIVITY_CHANGED")).isEqualTo(1);
            insert(statement, "PASSKEY_CHANGED");
            insert(statement, "PASSKEY_VERIFIED");
            assertThat(count(statement, "PASSKEY_CHANGED")).isEqualTo(1);
            assertThat(count(statement, "PASSKEY_VERIFIED")).isEqualTo(1);
            assertThatThrownBy(() -> insert(statement, "NOT_AN_AUTH_EVENT"))
                    .isInstanceOfSatisfying(
                            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION));
        }
    }

    // Applies the master changelog up to, but excluding, the constraint changeset, or all of it.
    private static void migrate(TestDatabase testDatabase, boolean complete) throws Exception {
        try (Connection connection = connect(testDatabase)) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
                // This fixture resumes a deliberately partial history before the production entry boundary.
                liquibase
                        .getDatabaseChangeLog()
                        .getPreconditions()
                        .getNestedPreconditions()
                        .clear();
                var contexts = new Contexts("dev");
                var labels = new LabelExpression();
                if (complete) {
                    liquibase.update(contexts);
                    return;
                }
                var pending = liquibase.listUnrunChangeSets(contexts, labels);
                int before = IntStream.range(0, pending.size())
                        .filter(index -> pending.get(index).getId().equals(CONSTRAINT_CHANGESET))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("The changelog has no " + CONSTRAINT_CHANGESET));
                liquibase.update(before, contexts, labels);
            }
        }
    }

    private static void insert(Statement statement, String eventType) throws SQLException {
        statement.executeUpdate("INSERT INTO auth_event (occurred_at, event_type, result, details) "
                + "VALUES ('2000-01-15', '" + eventType + "', 'SUCCESS', '{}')");
    }

    private static int count(Statement statement, String eventType) throws SQLException {
        try (ResultSet rows =
                statement.executeQuery("SELECT count(*) FROM auth_event WHERE event_type = '" + eventType + "'")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }
}
