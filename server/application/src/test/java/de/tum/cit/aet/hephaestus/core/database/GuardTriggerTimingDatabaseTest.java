package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A trigger that only validates runs AFTER the row write. A BEFORE row trigger makes PostgreSQL lock the row FOR
 * UPDATE whenever an UPDATE names a unique column, and that lock blocks every foreign-key write that references the
 * row until commit. A BEFORE trigger is allowed only where it must act before the write; each one names why.
 */
@Tag("database")
class GuardTriggerTimingDatabaseTest {
    private static final String CHANGELOG = "-rootfix-after-guard-triggers.xml";

    private static final Map<String, String> BEFORE_TRIGGERS = Map.of(
            "workspace.trg_reserve_workspace_slug", "reserves the slug before the row holds it",
            "problem.problem_parent_touch", "touches the parent result so that its own guard sees the change",
            "agent_run.agent_run_settles_calls", "settles the run's open calls before the delete removes the run");

    private static final String BEFORE_ROW_TRIGGERS = """
            SELECT c.relname || '.' || t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
            WHERE NOT t.tgisinternal AND c.relnamespace = 'public'::regnamespace
              AND (t.tgtype & 1) = 1 AND (t.tgtype & 2) = 2
            ORDER BY 1
            """;

    @Test
    void shouldRunEveryValidatingTriggerAfterTheRowWrite() throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase("prod");

        assertThat(PracticeMigrationFixture.column(database, BEFORE_ROW_TRIGGERS))
                .as("BEFORE row triggers; a guard that only validates belongs AFTER the write")
                .containsExactlyInAnyOrderElementsOf(BEFORE_TRIGGERS.keySet());
    }

    @Test
    void shouldStillRefuseAnItemThatNamesItselfAsItsParentAndSupportRollback() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "guard_trigger_timing_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        var validatingBefore = PracticeMigrationFixture.column(database, BEFORE_ROW_TRIGGERS);
        liquibase(database, liquibase -> PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG));

        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(979700,'w979700','W979700','w979700','ORG','ACTIVE');
                INSERT INTO item(id,kind,workspace_id) OVERRIDING SYSTEM VALUE VALUES(979701,'scm.issue',979700);
                """);
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.executeUpdate("INSERT INTO item(id,kind,workspace_id,parent_id)"
                            + " OVERRIDING SYSTEM VALUE VALUES(979702,'scm.issue',979700,979702)"))
                    .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
            assertThat(statement.executeUpdate("INSERT INTO item(id,kind,workspace_id,parent_id)"
                            + " OVERRIDING SYSTEM VALUE VALUES(979703,'scm.issue',979700,979701)"))
                    .isEqualTo(1);
        }

        liquibase(database, liquibase -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG));
        assertThat(PracticeMigrationFixture.column(database, BEFORE_ROW_TRIGGERS))
                .isEqualTo(validatingBefore);
    }

    private interface Migration {
        void run(Liquibase liquibase) throws Exception;
    }

    private static void liquibase(TestDatabase database, Migration migration) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            migration.run(liquibase);
        }
    }
}
