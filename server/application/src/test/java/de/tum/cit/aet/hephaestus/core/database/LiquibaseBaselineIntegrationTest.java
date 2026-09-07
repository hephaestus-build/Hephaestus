package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import liquibase.command.CommandScope;
import liquibase.command.core.DiffCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionCommandStep;
import liquibase.command.core.helpers.PreCompareCommandStep;
import liquibase.command.core.helpers.ReferenceDbUrlConnectionCommandStep;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.DiffResult;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class LiquibaseBaselineIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldInitializeRequiredDataAndPartitionMaintenanceWhenDatabaseIsEmpty(String context) throws Exception {
        TestDatabase database = emptyDatabase();
        update(database, "db/master.xml", context);

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
        List<String> history =
                query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        assertThat(history).hasSize(context.equals("prod") ? 4 : 3);
        update(database, "db/master.xml", context);
        assertThat(query(database, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(history);
    }

    @Test
    void shouldRejectPartialSchemaWithoutMarkingBaselineApplied() throws Exception {
        TestDatabase database = emptyDatabase();
        execute(database, "CREATE TABLE workspace (id bigint PRIMARY KEY)");

        assertThatThrownBy(() -> update(database, "db/master.xml", "prod"))
                .isInstanceOf(LiquibaseException.class)
                .hasStackTraceContaining(
                        "Existing databases must complete the baseline synchronization runbook before deployment.");

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
        update(database, "db/master.xml", "prod");
        execute(database, """
                UPDATE databasechangeloglock
                SET locked = true, lockgranted = now(), lockedby = 'simulated-stale-host' WHERE id = 1
                """);

        try (Liquibase liquibase = liquibase(database, "db/master.xml")) {
            liquibase.forceReleaseLocks();
        }

        assertThat(query(database, "SELECT locked::text FROM databasechangeloglock WHERE id = 1"))
                .containsExactly("false");
        update(database, "db/master.xml", "prod");
    }

    @Tag("slow")
    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldMatchArchivedSchemaAndSynchronizeWithoutDdlOrDataLoss(String context) throws Exception {
        TestDatabase archived = emptyDatabase();
        TestDatabase baseline = emptyDatabase();
        update(archived, "db/archive-master.xml", context);
        update(baseline, "db/master.xml", context);

        DiffResult diff = Objects.requireNonNull(new CommandScope(DiffCommandStep.COMMAND_NAME)
                .addArgumentValue(
                        PreCompareCommandStep.DIFF_TYPES_ARG,
                        "tables,columns,foreignkeys,indexes,primarykeys,sequences,uniqueconstraints,views")
                .addArgumentValue(DbUrlConnectionCommandStep.URL_ARG, baseline.jdbcUrl())
                .addArgumentValue(DbUrlConnectionCommandStep.USERNAME_ARG, baseline.username())
                .addArgumentValue(DbUrlConnectionCommandStep.PASSWORD_ARG, baseline.password())
                .addArgumentValue(ReferenceDbUrlConnectionCommandStep.REFERENCE_URL_ARG, archived.jdbcUrl())
                .addArgumentValue(ReferenceDbUrlConnectionCommandStep.REFERENCE_USERNAME_ARG, archived.username())
                .addArgumentValue(ReferenceDbUrlConnectionCommandStep.REFERENCE_PASSWORD_ARG, archived.password())
                .execute()
                .getResult(DiffCommandStep.DIFF_RESULT));
        assertThat(diff.getMissingObjects()).as("missing schema objects").isEmpty();
        assertThat(diff.getUnexpectedObjects()).as("unexpected schema objects").isEmpty();
        assertThat(diff.getChangedObjects()).as("changed schema objects").isEmpty();

        assertNativeSchemaEqual(archived, baseline, "functions", """
                SELECT pg_get_functiondef(p.oid) FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND NOT EXISTS (
                    SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass
                    AND d.objid = p.oid AND d.deptype = 'e')
                ORDER BY p.proname, pg_get_function_identity_arguments(p.oid)
                """);
        assertNativeSchemaEqual(archived, baseline, "triggers", """
                SELECT pg_get_triggerdef(t.oid) FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND NOT t.tgisinternal
                ORDER BY c.relname, t.tgname
                """);
        assertNativeExpressionsEqual(archived, baseline, "check and not-null constraints", """
                SELECT c.relname || ':' || k.contype::text || ':' ||
                       ARRAY(SELECT a.attname FROM unnest(k.conkey) WITH ORDINALITY AS key(attnum, position)
                             JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = key.attnum
                             ORDER BY key.position)::text || ':' ||
                       CASE WHEN k.contype = 'n' THEN '' ELSE k.conname END || ':' || pg_get_constraintdef(k.oid)
                FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND k.contype IN ('c', 'n')
                  AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock')
                ORDER BY c.relname, k.contype, k.conkey, pg_get_constraintdef(k.oid)
                """);
        assertNativeExpressionsEqual(archived, baseline, "index expressions and predicates", """
                SELECT c.relname || ':' || i.relname || ':' ||
                       coalesce(pg_get_expr(x.indexprs, x.indrelid), '') || ':' ||
                       coalesce(pg_get_expr(x.indpred, x.indrelid), '')
                FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
                JOIN pg_class c ON c.oid = x.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND (x.indexprs IS NOT NULL OR x.indpred IS NOT NULL)
                ORDER BY c.relname, i.relname
                """);
        assertNativeSchemaEqual(archived, baseline, "partition parents", """
                SELECT c.relname || ':' || pg_get_partkeydef(c.oid)
                FROM pg_partitioned_table p JOIN pg_class c ON c.oid = p.partrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                ORDER BY c.relname
                """);

        execute(archived, """
                UPDATE instance_settings SET silent_mode_engaged = false, silent_mode_reason = 'preserve operator choice'
                WHERE id = 1;
                CREATE FUNCTION reject_baseline_ddl() RETURNS event_trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synchronization must not execute DDL'; END $$;
                CREATE EVENT TRIGGER reject_baseline_ddl ON ddl_command_start EXECUTE FUNCTION reject_baseline_ddl();
                """);
        List<String> oldHistory =
                query(archived, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        try (Liquibase liquibase = liquibase(archived, "db/master.xml")) {
            liquibase.changeLogSync("baseline_v0_77_4", new Contexts(context), new LabelExpression());
        }
        update(archived, "db/master.xml", context);
        List<String> syncedHistory =
                query(archived, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted");
        assertThat(syncedHistory).startsWith(oldHistory.toArray(String[]::new));
        assertThat(syncedHistory).hasSize(oldHistory.size() + (context.equals("prod") ? 4 : 3));
        assertThat(query(
                        archived,
                        "SELECT silent_mode_engaged::text || ':' || silent_mode_reason FROM instance_settings"))
                .containsExactly("false:preserve operator choice");
        update(archived, "db/master.xml", context);
        assertThat(query(archived, "SELECT id || ':' || md5sum FROM databasechangelog ORDER BY orderexecuted"))
                .isEqualTo(syncedHistory);
    }

    private static void assertNativeSchemaEqual(
            TestDatabase reference, TestDatabase baseline, String objectType, String sql) throws SQLException {
        assertThat(query(baseline, sql)).as(objectType).isEqualTo(query(reference, sql));
    }

    private static void assertNativeExpressionsEqual(
            TestDatabase reference, TestDatabase baseline, String objectType, String sql) throws SQLException {
        assertThat(query(baseline, sql).stream()
                        .map(LiquibaseBaselineIntegrationTest::normalizeVarcharArrayCasts)
                        .sorted()
                        .toList())
                .as(objectType)
                .isEqualTo(query(reference, sql).stream()
                        .map(LiquibaseBaselineIntegrationTest::normalizeVarcharArrayCasts)
                        .sorted()
                        .toList());
    }

    private static String normalizeVarcharArrayCasts(String definition) {
        // pg_dump casts a varchar literal array as a whole; replay casts each literal to text.
        return definition
                .replaceAll("\\(('(?:[^']|'')*'::character varying)\\)::text", "$1")
                .replaceAll(
                        "\\(ARRAY\\[((?:'(?:[^']|'')*'::character varying(?:, )?)+)\\]\\)::text\\[\\]", "ARRAY[$1]");
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
