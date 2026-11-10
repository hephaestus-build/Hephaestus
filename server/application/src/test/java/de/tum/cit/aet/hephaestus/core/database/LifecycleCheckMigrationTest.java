package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A lifecycle column holds only the states its code writes, and a status holds only the columns it implies. */
@Tag("database")
class LifecycleCheckMigrationTest {

    private static final String CHANGELOG = "-fix-lifecycle-checks.xml";
    private static final int CHANGES = 11;
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("lifecycle_checks");

    @Test
    void shouldHaltOnAStateItCannotKeepThenRefuseItUntilTheChangelogRollsBack() throws Exception {
        try (Connection connection = connect();
                Liquibase liquibase = liquibase(connection)) {
            var unrun = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = 0;
            while (!unrun.get(before).getFilePath().endsWith(CHANGELOG)) before++;
            liquibase.update(before, CONTEXTS, LABELS);
            execute("""
                    INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                    VALUES (1,'lifecycle-checks','Lifecycle checks','lifecycle-checks','ORG','ARCHIVED');
                    INSERT INTO llm_call_pool (id, slug, display_name, created_at)
                    VALUES (1, 'pool', 'Pool', clock_timestamp());
                    """);

            assertThatThrownBy(() -> liquibase.update(CHANGES, CONTEXTS, LABELS))
                    .isInstanceOf(LiquibaseException.class);
            assertThat(constraint("ck_workspace_status")).isFalse();

            execute("UPDATE workspace SET status='SUSPENDED' WHERE id=1");
            liquibase.update(CHANGES, CONTEXTS, LABELS);

            assertThatThrownBy(() -> execute("UPDATE workspace SET status='ARCHIVED' WHERE id=1"))
                    .hasMessageContaining("ck_workspace_status");
            assertThatThrownBy(() -> permit("ACTIVE", "NULL")).hasMessageContaining("ck_llm_call_permit_granted");
            permit("ACTIVE", "clock_timestamp()");

            liquibase.rollback(CHANGES, CONTEXTS, LABELS);

            assertThat(constraint("ck_workspace_status")).isFalse();
            execute("UPDATE workspace SET status='ARCHIVED' WHERE id=1");
            permit("ACTIVE", "NULL");
        }
    }

    private static void permit(String state, String grantedAt) throws SQLException {
        execute("""
                INSERT INTO llm_call_permit (id, pool_id, worker_id, call_class, workspace_id, source_id, attempt,
                    state, acquired_at, renewed_at, granted_at, requests, tokens)
                VALUES (gen_random_uuid(), 1, 'worker', 'REQUEST', 1, gen_random_uuid(), 0, '%s', clock_timestamp(),
                    clock_timestamp(), %s, 0, 0)
                """.formatted(state, grantedAt));
    }

    private static boolean constraint(String name) throws SQLException {
        try (Connection connection = connect();
                var statement =
                        connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_constraint WHERE conname=?)")) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getBoolean(1);
            }
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database);
        // This fixture resumes a deliberately partial history before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
