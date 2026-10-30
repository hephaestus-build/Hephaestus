package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.diagnostics.FailureAnalysis;

@Tag("integration")
class LiquibaseBaselineIntegrationTest {

    // Baseline parity is anchored to the release, not the evolving master changelog.
    private static final String BASELINE = "db/changelog/0000000000000_baseline_v0_77_4.xml";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldInitializeRequiredDataAndPartitionMaintenanceWhenDatabaseIsEmpty(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        update(database, BASELINE, context);

        assertThat(query(database, "SELECT count(*)::text FROM account UNION ALL SELECT count(*)::text FROM workspace"))
                .containsExactly("0", "0");
        assertThat(query(database, "SELECT type || ':' || server_url FROM identity_provider ORDER BY type"))
                .containsExactly("GITHUB:https://github.com", "GITLAB:https://gitlab.com", "SLACK:https://slack.com");
        assertThat(query(database, "SELECT silent_mode_engaged::text FROM instance_settings WHERE id = 1"))
                .containsExactly("true");
        assertThat(query(database, "SELECT allow_workspace_connections::text FROM instance_llm_settings WHERE id = 1"))
                .containsExactly("true");
        assertThat(query(database, "SELECT version || ':' || sha256 FROM consent_notice"))
                .containsExactly("2026-08-30:037a674668d981d7ef421bb5d47bb39728e8a493b37e41987ef88299356f1d0b");
        assertThat(query(database, """
            SELECT retention || ':' || retention_keep_table::text || ':' ||
                   retention_keep_index::text || ':' || infinite_time_partitions::text
            FROM partman.part_config WHERE parent_table = 'public.auth_event'
            """)).containsExactly("12 months:false:false:true");
        assertThat(query(database, """
            SELECT count(*)::text FROM pg_trigger
            WHERE tgrelid = 'public.auth_event'::regclass AND tgname = 'trg_auth_event_block_mutation'
            """)).containsExactly(context.equals("prod") ? "1" : "0");
        execute(database, """
            INSERT INTO auth_event (event_type, result) VALUES ('LOGIN', 'SUCCESS');
            """);
        assertThat(query(database, "SELECT (tableoid <> 'public.auth_event_default'::regclass)::text FROM auth_event"))
                .containsExactly("true");
        if (context.equals("prod")) {
            assertThatThrownBy(() -> execute(database, "UPDATE auth_event SET details = '{}'::jsonb"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("auth_event is append-only");
            assertThatThrownBy(() -> execute(database, "DELETE FROM auth_event"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("auth_event is append-only");
        } else {
            execute(database, "UPDATE auth_event SET details = '{}'::jsonb");
            execute(database, "DELETE FROM auth_event");
        }
        List<String> history =
                query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        assertThat(history).hasSize(context.equals("prod") ? 4 : 3);
        update(database, BASELINE, context);
        assertThat(query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(history);
    }

    @Test
    void shouldRejectPartialSchemaWithoutMarkingBaselineApplied() throws Exception {
        TestDatabase database = emptyDatabase();
        execute(database, "CREATE TABLE workspace (id bigint PRIMARY KEY)");

        Throwable refusal = catchThrowable(() -> update(database, BASELINE, "prod"));
        assertThat(refusal)
                .isInstanceOf(LiquibaseException.class)
                .hasStackTraceContaining(
                        "Existing databases must complete the baseline synchronization runbook before deployment.");
        // The changeset's own precondition halts the update when it runs, after validation passed.
        assertThat(new LiquibaseRefusalFailureAnalyzer().analyze(refusal))
                .extracting(FailureAnalysis::getDescription)
                .asString()
                .contains("Existing databases must complete the baseline synchronization runbook before deployment.");

        assertThat(query(database, "SELECT count(*)::text FROM databasechangelog"))
                .containsExactly("0");
        assertThat(query(
                        database,
                        "SELECT count(*)::text FROM information_schema.columns WHERE table_name = 'workspace'"))
                .containsExactly("1");
    }

    @Test
    void shouldRecoverStaleLockAndAllowSubsequentUpdate() throws Exception {
        TestDatabase database = emptyDatabase();
        update(database, BASELINE, "prod");
        execute(database, """
            UPDATE databasechangeloglock
            SET locked = true, lockgranted = now(), lockedby = 'simulated-stale-host' WHERE id = 1
            """);

        try (Liquibase liquibase = liquibase(database, BASELINE)) {
            liquibase.forceReleaseLocks();
        }

        assertThat(query(database, "SELECT locked::text FROM databasechangeloglock WHERE id = 1"))
                .containsExactly("false");
        update(database, BASELINE, "prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldRejectPreCutPointHistoryForUpdateAndSynchronization(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        update(database, BASELINE, context);
        List<String> history =
                query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        execute(database, """
            CREATE FUNCTION reject_upgrade_ddl() RETURNS event_trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'refused upgrade must not execute DDL'; END $$;
            CREATE EVENT TRIGGER reject_upgrade_ddl ON ddl_command_start EXECUTE FUNCTION reject_upgrade_ddl();
            """);

        Throwable refusal = catchThrowable(() -> update(database, "db/master.xml", context));
        assertThat(refusal)
                .isInstanceOf(LiquibaseException.class)
                .hasStackTraceContaining("Existing databases must first run v0.95.0");
        // Logs drop exception messages, so the startup failure analysis is where an operator reads the refusal.
        assertThat(new LiquibaseRefusalFailureAnalyzer().analyze(refusal))
                .extracting(FailureAnalysis::getDescription)
                .asString()
                .contains("Existing databases must first run v0.95.0");
        try (Liquibase liquibase = liquibase(database, "db/master.xml")) {
            assertThatThrownBy(() ->
                            liquibase.changeLogSync("baseline_v0_77_4", new Contexts(context), new LabelExpression()))
                    .isInstanceOf(LiquibaseException.class)
                    .hasStackTraceContaining("Existing databases must first run v0.95.0");
        }

        assertThat(query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(history);
        assertThat(query(database, "SELECT locked::text FROM databasechangeloglock WHERE id = 1"))
                .containsExactly("false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"author", "filename"})
    void shouldRejectCutPointWithWrongMigrationIdentity(String field) throws Exception {
        TestDatabase database = emptyDatabase();
        initializeThroughCutPoint(database, "prod");
        // Only the identity is false: a complete schema must not turn a false history into proof.
        execute(
                database,
                "UPDATE databasechangelog SET " + field
                        + " = 'not-the-released-identity' WHERE id = '1793208610071-3'");
        List<String> history = query(
                database,
                "SELECT id || ':' || author || ':' || filename || ':' || md5sum "
                        + "FROM databasechangelog ORDER BY orderexecuted");

        assertThatThrownBy(() -> update(database, "db/master.xml", "prod"))
                .isInstanceOf(LiquibaseException.class)
                .hasStackTraceContaining("Existing databases must first run v0.95.0");
        try (Liquibase liquibase = liquibase(database, "db/master.xml")) {
            assertThatThrownBy(() ->
                            liquibase.changeLogSync("baseline_v0_77_4", new Contexts("prod"), new LabelExpression()))
                    .isInstanceOf(LiquibaseException.class)
                    .hasStackTraceContaining("Existing databases must first run v0.95.0");
        }
        assertThat(query(
                        database,
                        "SELECT id || ':' || author || ':' || filename || ':' || md5sum "
                                + "FROM databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldApplyMasterToFreshDatabases(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        update(database, "db/master.xml", context);
        List<String> history =
                query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        assertThat(history).anyMatch(row -> row.startsWith("1793208610071-3:"));
        update(database, "db/master.xml", context);
        assertThat(query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldUpgradeTheReleasedCutPointAndPreserveSettings(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        initializeThroughCutPoint(database, context);
        execute(database, "UPDATE instance_settings SET silent_mode_engaged = false WHERE id = 1");
        update(database, "db/master.xml", context);
        assertThat(query(database, "SELECT silent_mode_engaged::text FROM instance_settings WHERE id = 1"))
                .containsExactly("false");
    }

    private static void initializeThroughCutPoint(TestDatabase database, String context) throws Exception {
        try (Liquibase liquibase = liquibase(database, "db/master.xml")) {
            var changes = liquibase.getDatabaseChangeLog().getChangeSets();
            int cutPoint = -1;
            for (int index = 0; index < changes.size(); index++) {
                if (changes.get(index).getId().equals("1793208610071-3")) {
                    cutPoint = index;
                    break;
                }
            }
            assertThat(cutPoint)
                    .as("the final v0.95.0 changeset remains in the released chain")
                    .isNotNegative();
            changes.subList(cutPoint + 1, changes.size()).clear();
            liquibase.update(new Contexts(context));
        }
    }

    private static TestDatabase emptyDatabase() {
        return PostgreSQLTestContainer.createDatabase(
                "baseline_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Liquibase liquibase(TestDatabase database, String changelog) throws Exception {
        return new Liquibase(
                changelog,
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(open(database))));
    }

    private static void update(TestDatabase database, String changelog, String context) throws Exception {
        try (Liquibase liquibase = liquibase(database, changelog)) {
            liquibase.update(new Contexts(context));
        }
    }

    private static Connection open(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static void execute(TestDatabase database, String sql) throws SQLException {
        try (Connection connection = open(database);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> query(TestDatabase database, String sql) throws SQLException {
        try (Connection connection = open(database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(Objects.requireNonNull(rows.getString(1)));
            }
            return values;
        }
    }
}
