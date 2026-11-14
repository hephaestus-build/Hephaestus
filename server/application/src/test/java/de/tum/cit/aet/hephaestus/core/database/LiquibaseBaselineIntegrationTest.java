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

/**
 * The 1.0 baseline replaced the 0.x chain. A new database runs it; a database on 1.0.0-rc.1 is recorded on it only if
 * its schema equals the pin; every other database is refused before anything changes.
 */
@Tag("integration")
class LiquibaseBaselineIntegrationTest {

    private static final String MASTER = "db/master.xml";
    private static final String BASELINE = "db/changelog/1.0.0/baseline.xml";
    private static final String TAG = "1.0.0-baseline";
    private static final String RC1_LAST_FILE = "db/changelog/next/20261101103000-erasure-history-redaction.xml";
    private static final String RC1_LAST = "1793504565891-1";
    private static final String GUARD_REFUSAL = "install 1.0.0-rc.1 and complete its upgrade first";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldSeedTheRowsOfRc1AndNothingElseWhenADatabaseIsNew(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        updateToBaseline(database, context);

        assertThat(query(database, "SELECT type || ':' || server_url FROM identity_provider ORDER BY id"))
                .containsExactly("GITHUB:https://github.com", "GITLAB:https://gitlab.com", "SLACK:https://slack.com");
        assertThat(query(database, "SELECT nextval('git_provider_id_seq')::text"))
                .containsExactly("4");
        assertThat(query(database, """
            SELECT concat_ws(':', id, silent_mode_engaged, silent_mode_reason, silent_mode_changed_at, version,
                silent_mode_changed_by_account_id, public_activity_allowed) FROM instance_settings
            """)).containsExactly("1:t:0");
        assertThat(query(database, """
            SELECT concat_ws(':', id, allowed_egress_hosts, allow_workspace_connections, updated_at, updated_by_account_id)
            FROM instance_llm_settings
            """)).containsExactly("1:t");
        assertThat(query(database, """
            SELECT concat_ws(':', parent_table, control, partition_interval, premake, retention, retention_keep_table,
                retention_keep_index, infinite_time_partitions)
            FROM partman.part_config
            """)).containsExactly("public.auth_event:occurred_at:1 mon:2:12 months:f:f:t");
        assertThat(query(database, """
            SELECT relname FROM pg_class
            WHERE relnamespace = 'public'::regnamespace AND relkind = 'r' AND NOT relispartition
              AND relname NOT IN ('identity_provider', 'instance_settings', 'instance_llm_settings',
                                  'databasechangelog', 'databasechangeloglock')
              AND (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I', relname), false, true, '')))[1]::text::bigint > 0
            """)).as("every other table starts empty").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldAppendOnlyAuditRowsInProdAndRunPartitionsWhenADatabaseIsNew(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        updateToBaseline(database, context);

        execute(database, "INSERT INTO auth_event (event_type, result) VALUES ('LOGIN', 'SUCCESS')");
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldInstallTheWholeChainOnceWhenADatabaseIsNew(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        update(database, context);
        List<String> history = history(database);
        assertThat(history).anyMatch(row -> row.startsWith(BASELINE + "::1.0.0-tag:"));

        update(database, context);

        assertThat(history(database)).isEqualTo(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldEndInTheSameSchemaWhenADatabaseOnRc1Upgrades(String context) throws Exception {
        TestDatabase fresh = emptyDatabase();
        migrate(fresh, context);
        TestDatabase onRc1 = rc1Database(context);
        execute(onRc1, "UPDATE instance_settings SET silent_mode_engaged = false WHERE id = 1");
        List<String> rc1History = query(onRc1, "SELECT filename || '::' || id FROM databasechangelog ORDER BY 1");

        migrate(onRc1, context);

        assertThat(fingerprint(onRc1)).isEqualTo(fingerprint(fresh));
        assertThat(history(onRc1)).contains(history(fresh).toArray(String[]::new));
        assertThat(query(onRc1, "SELECT filename || '::' || id FROM databasechangelog ORDER BY 1"))
                .as("the older history stays")
                .containsAll(rc1History);
        assertThat(query(onRc1, "SELECT silent_mode_engaged::text FROM instance_settings WHERE id = 1"))
                .containsExactly("false");

        List<String> synchronized_ = history(onRc1);
        migrate(onRc1, context);
        assertThat(history(onRc1)).isEqualTo(synchronized_);
    }

    @Test
    void shouldFinishWhenASynchronizationDiedHalfWay() throws Exception {
        TestDatabase database = rc1Database("prod");
        execute(database, """
            INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype)
            SELECT '1.0.0-schema', 'hephaestus', '%s', now(), max(orderexecuted) + 1, 'EXECUTED' FROM databasechangelog
            """.formatted(BASELINE));

        migrate(database, "prod");

        assertThat(query(
                        database,
                        "SELECT tag FROM databasechangelog WHERE filename = '" + BASELINE + "' AND tag IS NOT NULL"))
                .containsExactly(TAG);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DROP INDEX idx_conversation_turn_leased_until",
                "ALTER TABLE workspace ADD COLUMN operator_note text",
                "ALTER TABLE workspace ALTER COLUMN display_name DROP NOT NULL",
                "ALTER TABLE workspace DROP CONSTRAINT ck_workspace_feedback_mode"
            })
    void shouldRefuseWithoutChangesWhenASchemaOnRc1Drifted(String drift) throws Exception {
        TestDatabase database = rc1Database("prod");
        execute(database, drift);
        rejectDdl(database);
        List<String> history = history(database);

        assertThatThrownBy(() -> migrate(database, "prod"))
                .rootCause()
                .hasMessageContaining("differs from 1.0.0-rc.1")
                .hasMessageContaining("The upgrade changed nothing");

        assertThat(history(database)).isEqualTo(history);
    }

    @Test
    void shouldRefuseWithoutChangesWhenABuildLaterThanRc1RanOnTheDatabase() throws Exception {
        TestDatabase database = rc1Database("prod");
        execute(database, """
            INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype)
            SELECT 'later', 'hephaestus', 'db/changelog/next/20261101110000-durable-practice-staging.xml', now(),
                   max(orderexecuted) + 1, 'EXECUTED' FROM databasechangelog
            """);
        rejectDdl(database);
        List<String> history = history(database);

        assertThatThrownBy(() -> migrate(database, "prod"))
                .rootCause()
                .hasMessageContaining("a build later than 1.0.0-rc.1")
                .hasMessageContaining("20261101110000-durable-practice-staging.xml");

        assertThat(history(database)).isEqualTo(history);
    }

    @Test
    void shouldIgnoreOlderHistoryWhenADatabaseOnRc1CameThroughASquash() throws Exception {
        TestDatabase database = rc1Database("prod");
        execute(database, """
            INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype)
            SELECT 'before-the-squash', 'someone', 'db/changelog/1700000000000_changelog.xml', now(),
                   max(orderexecuted) + 1, 'EXECUTED' FROM databasechangelog
            """);

        migrate(database, "prod");

        assertThat(query(database, "SELECT count(*)::text FROM databasechangelog WHERE tag = '" + TAG + "'"))
                .containsExactly("1");
    }

    @Test
    void shouldUpgradeADatabaseOnRc1ThatADumpRestoredAndAnOperatorExtended() throws Exception {
        TestDatabase original = rc1Database("prod");
        TestDatabase restored = emptyDatabase();
        // pg_upgrade and a logical restore both rebuild the schema from pg_dump, which prints some checks differently.
        var dump = PostgreSQLTestContainer.getInstance()
                .execInContainer(
                        "sh",
                        "-c",
                        "pg_dump -U " + original.username() + " -Fc " + name(original) + " | pg_restore -U "
                                + restored.username() + " --no-owner -d " + name(restored));
        assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
        execute(restored, "CREATE EXTENSION pg_stat_statements");

        migrate(restored, "prod");

        assertThat(query(restored, "SELECT count(*)::text FROM databasechangelog WHERE tag = '" + TAG + "'"))
                .containsExactly("1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldRefuseWithoutChangesWhenADatabaseIsOlderThanRc1(String context) throws Exception {
        TestDatabase database = rc1Database(context);
        execute(database, "DELETE FROM databasechangelog WHERE id = '" + RC1_LAST + "'");
        rejectDdl(database);
        List<String> history = history(database);

        Throwable refusal = catchThrowable(() -> update(database, context));
        assertThat(refusal).isInstanceOf(LiquibaseException.class).hasStackTraceContaining(GUARD_REFUSAL);
        // Logs drop exception messages, so the startup failure analysis is where an operator reads the refusal.
        assertThat(new LiquibaseRefusalFailureAnalyzer().analyze(refusal))
                .extracting(FailureAnalysis::getDescription)
                .asString()
                .contains(GUARD_REFUSAL);
        assertThatThrownBy(() -> migrate(database, context)).hasStackTraceContaining(GUARD_REFUSAL);
        try (Liquibase liquibase = liquibase(database)) {
            assertThatThrownBy(() -> liquibase.changeLogSync(TAG, new Contexts(context), new LabelExpression()))
                    .isInstanceOf(LiquibaseException.class)
                    .hasStackTraceContaining(GUARD_REFUSAL);
        }

        assertThat(history(database)).isEqualTo(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"author", "filename"})
    void shouldRefuseAFalseIdentityOfTheLastRc1ChangeSet(String field) throws Exception {
        TestDatabase database = rc1Database("prod");
        // Only the identity is false: a complete schema must not turn a false history into proof.
        execute(database, "UPDATE databasechangelog SET " + field + " = 'false' WHERE id = '" + RC1_LAST + "'");

        assertThatThrownBy(() -> migrate(database, "prod")).hasStackTraceContaining(GUARD_REFUSAL);
    }

    @Test
    void shouldStopWithoutRecordingTheBaselineWhenASchemaExistsWithoutHistory() throws Exception {
        TestDatabase database = emptyDatabase();
        execute(database, "CREATE TABLE workspace (id bigint PRIMARY KEY)");

        Throwable refusal = catchThrowable(() -> update(database, "prod"));
        String expected = "This database already has a schema, but no 1.0 baseline";
        assertThat(refusal).isInstanceOf(LiquibaseException.class).hasStackTraceContaining(expected);
        assertThat(new LiquibaseRefusalFailureAnalyzer().analyze(refusal))
                .extracting(FailureAnalysis::getDescription)
                .asString()
                .contains(expected);

        assertThat(query(database, "SELECT count(*)::text FROM databasechangelog"))
                .containsExactly("0");
    }

    @Test
    void shouldStopForLocalDatabaseWhenMigratingAtStartOnRc1() throws Exception {
        TestDatabase database = rc1Database("dev");

        assertThatThrownBy(() -> update(database, "dev"))
                .isInstanceOf(LiquibaseException.class)
                .hasStackTraceContaining("vp run dev:reset");
    }

    @Test
    void shouldRecoverStaleLockAndAllowSubsequentUpdate() throws Exception {
        TestDatabase database = emptyDatabase();
        update(database, "prod");
        execute(database, """
            UPDATE databasechangeloglock
            SET locked = true, lockgranted = now(), lockedby = 'simulated-stale-host' WHERE id = 1
            """);

        try (Liquibase liquibase = liquibase(database)) {
            liquibase.forceReleaseLocks();
        }

        assertThat(query(database, "SELECT locked::text FROM databasechangeloglock WHERE id = 1"))
                .containsExactly("false");
        update(database, "prod");
    }

    /** What 1.0.0-rc.1 leaves: the baseline's schema and rows, under the history rows of the old chain. */
    private static TestDatabase rc1Database(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        updateToBaseline(database, context);
        execute(database, """
            DELETE FROM databasechangelog;
            INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype)
            VALUES ('1793208610071-3', 'hephaestus', 'db/changelog/1793208610071_changelog.xml', now(), 1, 'EXECUTED'),
                   ('%s', 'hephaestus', '%s', now(), 2, 'EXECUTED');
            """.formatted(RC1_LAST, RC1_LAST_FILE));
        return database;
    }

    private static void rejectDdl(TestDatabase database) throws SQLException {
        execute(database, """
            CREATE FUNCTION reject_upgrade_ddl() RETURNS event_trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'refused upgrade must not execute DDL'; END $$;
            CREATE EVENT TRIGGER reject_upgrade_ddl ON ddl_command_start EXECUTE FUNCTION reject_upgrade_ddl();
            """);
    }

    /** The migrate run mode, as the migration job starts it. */
    private static void migrate(TestDatabase database, String context) {
        assertThat(DatabaseMigration.run(new String[] {
                    "--spring.profiles.active=prod," + DatabaseMigration.PROFILE,
                    "--spring.liquibase.contexts=" + context,
                    "--spring.datasource.url=" + database.jdbcUrl(),
                    "--spring.datasource.username=" + database.username(),
                    "--spring.datasource.password=" + database.password(),
                    "--spring.main.banner-mode=off"
                }))
                .isZero();
    }

    private static List<String> fingerprint(TestDatabase database) throws SQLException {
        try (Connection connection = open(database)) {
            return SchemaFingerprint.of(connection);
        }
    }

    private static List<String> history(TestDatabase database) throws SQLException {
        return query(
                database,
                "SELECT filename || '::' || id || ':' || coalesce(md5sum, '') FROM databasechangelog ORDER BY orderexecuted, id");
    }

    private static String name(TestDatabase database) {
        return database.jdbcUrl().substring(database.jdbcUrl().lastIndexOf('/') + 1);
    }

    private static TestDatabase emptyDatabase() {
        return PostgreSQLTestContainer.createDatabase(
                "baseline_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        return new Liquibase(
                MASTER,
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(open(database))));
    }

    private static void update(TestDatabase database, String context) throws Exception {
        try (Liquibase liquibase = liquibase(database)) {
            liquibase.update(new Contexts(context));
        }
    }

    private static void updateToBaseline(TestDatabase database, String context) throws Exception {
        try (Liquibase liquibase = liquibase(database)) {
            liquibase.update(TAG, new Contexts(context), new LabelExpression());
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
