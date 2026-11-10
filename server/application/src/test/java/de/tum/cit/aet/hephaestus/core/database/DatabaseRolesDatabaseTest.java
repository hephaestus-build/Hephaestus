package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The roles of the chart's {@code files/database-roles.sql}: the owner migrates, and the application role reads and
 * writes rows, owns nothing and holds no attribute that escapes a grant.
 */
@Tag("database")
class DatabaseRolesDatabaseTest {

    /** A fresh install: the roles first, then the owner migrates the empty database. */
    private static final TestDatabase INSTALLED = install();

    private static TestDatabase install() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_roles_installed");
        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_installed");
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        return database;
    }

    @Test
    void shouldGiveNeitherRoleAnAttributeThatEscapesAGrantWhenTheRolesAreSetUp() throws SQLException {
        try (Connection connection = connect(INSTALLED)) {
            List<@Nullable String> escapes = jdbc(connection).queryForList("""
                            SELECT rolname FROM pg_roles
                            WHERE rolname IN ('hephaestus_owner', 'hephaestus_app')
                              AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
                            """, String.class);
            assertThat(escapes).isEmpty();
            assertThat(jdbc(connection)
                            .queryForObject(
                                    "SELECT pg_has_role('hephaestus_app', 'hephaestus_owner', 'MEMBER')",
                                    Boolean.class))
                    .isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CREATE TABLE public.role_probe (id bigint)",
                "CREATE SCHEMA role_probe",
                "ALTER TABLE public.workspace ADD COLUMN role_probe bigint",
                "CREATE INDEX role_probe ON public.workspace (id)",
                "DROP TABLE public.workspace",
                "TRUNCATE public.workspace",
                "CREATE FUNCTION public.role_probe() RETURNS int LANGUAGE sql AS 'SELECT 1'",
                "CALL partman.run_maintenance_proc()",
                "SELECT rolpassword FROM pg_authid",
                "ALTER ROLE hephaestus_app BYPASSRLS",
            })
    void shouldRefuseTheApplicationRoleWhenItChangesTheSchemaOrReadsTheRoleCatalog(String statement)
            throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(INSTALLED))) {
            assertThatThrownBy(() -> jdbc(connection).execute(statement))
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void shouldHoldTheApplicationRoleToRowLevelSecurityWhenATableEnablesIt() throws SQLException {
        try (Connection owner = connect(PostgreSQLTestContainer.asOwner(INSTALLED))) {
            var asOwner = jdbc(owner);
            asOwner.execute("CREATE TABLE public.role_probe_rls (id bigint PRIMARY KEY, visible boolean NOT NULL)");
            asOwner.execute("ALTER TABLE public.role_probe_rls ENABLE ROW LEVEL SECURITY");
            asOwner.execute("CREATE POLICY visible_rows ON public.role_probe_rls USING (visible)");
            asOwner.execute("INSERT INTO public.role_probe_rls VALUES (1, true), (2, false)");
        }
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(INSTALLED))) {
            var asApplication = jdbc(connection);
            // The owner's default privileges gave the application role this new table's rows.
            assertThat(asApplication.queryForList("SELECT id FROM public.role_probe_rls", Long.class))
                    .containsExactly(1L);
            asApplication.execute("SET row_security = off");
            assertThatThrownBy(() -> asApplication.queryForList("SELECT id FROM public.role_probe_rls", Long.class))
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void shouldLetTheApplicationRoleUseRowsAndKeepPartitionsWhenTheOwnerMigrated() throws SQLException {
        assertApplicationWorks(INSTALLED);
    }

    /** A database that the administrator migrated, as every installation before 1.0 did. */
    @Test
    void shouldMoveEveryObjectToTheOwnerWhenTheAdministratorMigratedTheDatabase() throws SQLException {
        TestDatabase database = PostgreSQLTestContainer.createMigratedDatabase("hephaestus_roles_moved");

        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_moved");

        try (Connection connection = connect(database)) {
            List<@Nullable String> elsewhere = jdbc(connection).queryForList("""
                            SELECT c.oid::regclass::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname IN ('public', 'partman') AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                              AND c.relowner <> 'hephaestus_owner'::regrole
                              AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
                                              AND d.objid = c.oid AND d.deptype = 'e')
                            UNION ALL
                            SELECT p.oid::regprocedure::text FROM pg_proc p
                            JOIN pg_namespace n ON n.oid = p.pronamespace
                            WHERE n.nspname IN ('public', 'partman') AND p.proowner <> 'hephaestus_owner'::regrole
                              AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass
                                              AND d.objid = p.oid AND d.deptype = 'e')
                            """, String.class);
            assertThat(elsewhere).isEmpty();
        }
        // The owner migrates it from now on: Liquibase writes its history and lock tables as the owner.
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        assertApplicationWorks(database);
    }

    /** The bundled image preloads pg_stat_statements, which records each utility statement for pg_monitor. */
    @Test
    void shouldKeepThePasswordsOutOfTheStatementStatisticsWhenTheRolesAndTheMonitorAreSetUp() throws SQLException {
        String canary = "canary-" + UUID.randomUUID();
        String monitorPassword = "monitor-" + UUID.randomUUID();
        try (Connection connection = connect(INSTALLED)) {
            var asAdministrator = jdbc(connection);
            recordStatements(asAdministrator);
            asAdministrator.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'roles_monitor') THEN
                            CREATE ROLE roles_monitor LOGIN IN ROLE pg_monitor;
                        END IF;
                    END $$""");
            asAdministrator.execute("GRANT USAGE ON SCHEMA monitoring TO roles_monitor");
            // Without the settings of database-quiet.sql, the statistics keep a password in plain text.
            asAdministrator.execute("ALTER ROLE roles_monitor PASSWORD '" + canary + "'");
        }

        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_installed");
        var monitor = PostgreSQLTestContainer.runChartScriptAs(
                "database-monitor.sh",
                "hephaestus_roles_installed",
                PostgreSQLTestContainer.administrator().username(),
                Map.of("DATABASE_MONITOR_PASSWORD", monitorPassword));
        assertThat(monitor.getExitCode()).as(monitor.getStderr()).isZero();

        TestDatabase asMonitorRole = new TestDatabase(INSTALLED.jdbcUrl(), "roles_monitor", canary);
        try (Connection connection = connect(asMonitorRole)) {
            var asMonitor = jdbc(connection);
            assertThat(recorded(asMonitor, canary)).isPositive();
            for (String password : List.of(
                    PostgreSQLTestContainer.asOwner(INSTALLED).password(),
                    PostgreSQLTestContainer.asApplication(INSTALLED).password(),
                    monitorPassword)) {
                assertThat(recorded(asMonitor, password)).isZero();
                assertThat(PostgreSQLTestContainer.getLogs()).doesNotContain(password);
            }
        }
        // The exporter logs in with the password that the file set.
        try (Connection connection =
                connect(new TestDatabase(INSTALLED.jdbcUrl(), "hephaestus_monitor", monitorPassword))) {
            assertThat(jdbc(connection).queryForObject("SELECT pg_has_role('pg_monitor', 'USAGE')", Boolean.class))
                    .isTrue();
        }
    }

    /**
     * The root of the fix: psql sends only SCRAM verifiers. With every setting that records a statement turned on and
     * without the quiet settings, no plaintext password reaches the log, the statement statistics or a command line.
     */
    @Test
    void shouldSendNoPlaintextPasswordWhenEveryRecordIsOnAndTheQuietSettingsAreSkipped() throws SQLException {
        String commandLines = "/tmp/command-lines-" + UUID.randomUUID();
        String canary = "canary-" + UUID.randomUUID();
        Map<String, String> passwords = rolePasswords();
        try (Connection connection = connect(INSTALLED)) {
            var asSuperuser = jdbc(connection);
            recordStatements(asSuperuser);
            recordEverything(asSuperuser, true);
            try {
                // Skips database-quiet.sql: its marker comes from the command line instead.
                String chart = PostgreSQLTestContainer.chartSqlScript(
                        "hephaestus_roles_installed",
                        PostgreSQLTestContainer.administrator().username(),
                        passwords.keySet(),
                        List.of("database-roles.sql"),
                        List.of("-v", "hephaestus_quiet=true"));
                var environment = new HashMap<>(passwords);
                environment.put("COMMAND_LINE_CANARY", canary);
                // PostgreSQL is the container's first process and adopts each orphan, and it restarts when one
                // ends by a signal: the sampler stops by a flag, and the shell waits for it.
                var result = PostgreSQLTestContainer.execShell(
                        "( while [ ! -e " + commandLines + ".stop ]; do cat /proc/[0-9]*/cmdline 2>/dev/null"
                                + " | tr '\\000' ' '; echo; done ) > " + commandLines + " &\n"
                                + "sampler=$!\ntrap 'touch " + commandLines + ".stop; wait $sampler' EXIT\n"
                                // A control: a command line that does hold a secret, which the sampler must see.
                                + "sh -c 'sleep 0.5' \"$COMMAND_LINE_CANARY\"\n"
                                + chart,
                        environment);
                assertThat(result.getExitCode()).as(result.getStderr()).isZero();
            } finally {
                recordEverything(asSuperuser, false);
            }
            String sampled = PostgreSQLTestContainer.execShell("cat " + commandLines, Map.of())
                    .getStdout();
            assertThat(sampled).as("the sampler saw the control").contains(canary);
            String logs = PostgreSQLTestContainer.getLogs();
            // The log and the statistics hold the statements, with the verifiers that the run set.
            for (String role : List.of("hephaestus_owner", "hephaestus_app")) {
                String verifier = verifierOf(asSuperuser, role);
                assertThat(verifier).startsWith("SCRAM-SHA-256$4096:");
                assertThat(logs).contains(verifier);
            }
            for (String password : passwords.values()) {
                assertThat(logs).doesNotContain(password);
                assertThat(recorded(asSuperuser, password)).isZero();
                assertThat(sampled).doesNotContain(password);
            }
        }
    }

    /** Defense in depth: with the quiet settings, not even a verifier reaches a record. */
    @Test
    void shouldRecordNoVerifierWhenEveryRecordIsOnAndTheQuietSettingsRun() throws SQLException {
        try (Connection connection = connect(INSTALLED)) {
            var asSuperuser = jdbc(connection);
            recordStatements(asSuperuser);
            recordEverything(asSuperuser, true);
            try {
                PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_installed");
            } finally {
                recordEverything(asSuperuser, false);
            }
            String logs = PostgreSQLTestContainer.getLogs();
            for (String role : List.of("hephaestus_owner", "hephaestus_app")) {
                String verifier = verifierOf(asSuperuser, role);
                assertThat(logs).doesNotContain(verifier);
                assertThat(recorded(asSuperuser, verifier)).isZero();
            }
            for (String password : rolePasswords().values()) {
                assertThat(logs).doesNotContain(password);
                assertThat(recorded(asSuperuser, password)).isZero();
            }
        }
    }

    @Test
    void shouldLetEachRoleSignInWithItsPasswordOverScramWhenOnlyItsVerifierWasSent() throws SQLException {
        String monitorPassword = "monitor-" + UUID.randomUUID();
        String before;
        try (Connection connection = connect(INSTALLED)) {
            before = verifierOf(jdbc(connection), "hephaestus_owner");
        }
        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_installed");
        var monitor = PostgreSQLTestContainer.runChartScriptAs(
                "database-monitor.sh",
                "hephaestus_roles_installed",
                PostgreSQLTestContainer.administrator().username(),
                Map.of("DATABASE_MONITOR_PASSWORD", monitorPassword));
        assertThat(monitor.getExitCode()).as(monitor.getStderr()).isZero();

        try (Connection connection = connect(INSTALLED)) {
            var asSuperuser = jdbc(connection);
            // Each run draws a new salt, as PostgreSQL itself does.
            assertThat(verifierOf(asSuperuser, "hephaestus_owner")).isNotEqualTo(before);
            for (String role : List.of("hephaestus_owner", "hephaestus_app", "hephaestus_monitor")) {
                assertThat(verifierOf(asSuperuser, role)).startsWith("SCRAM-SHA-256$4096:");
            }
        }
        for (TestDatabase login : List.of(
                PostgreSQLTestContainer.asOwner(INSTALLED),
                PostgreSQLTestContainer.asApplication(INSTALLED),
                new TestDatabase(INSTALLED.jdbcUrl(), "hephaestus_monitor", monitorPassword))) {
            try (Connection connection = connect(login)) {
                assertThat(jdbc(connection).queryForObject("SELECT current_user", String.class))
                        .isEqualTo(login.username());
            }
            assertThatThrownBy(() -> connect(new TestDatabase(login.jdbcUrl(), login.username(), "wrong")))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            refused -> assertThat(refused.getSQLState()).isEqualTo("28P01"));
        }
    }

    /** The scripts hand psql the verifiers alone: the process that talks to PostgreSQL never holds a password. */
    @Test
    void shouldGivePsqlNoPasswordWhenAChartScriptRuns() {
        String shimDirectory = "/tmp/no-password-psql-" + UUID.randomUUID();
        // A psql that prints the environment it got instead of connecting.
        String shim = "mkdir -p " + shimDirectory + " && printf '#!/bin/sh\\nenv\\n' > " + shimDirectory
                + "/psql && chmod +x " + shimDirectory + "/psql && PATH=" + shimDirectory + ":$PATH ";
        Map<String, String> monitor = Map.of("DATABASE_MONITOR_PASSWORD", "monitor-" + UUID.randomUUID());
        for (var run :
                List.of(Map.entry("database-roles.sh", rolePasswords()), Map.entry("database-monitor.sh", monitor))) {
            var result =
                    PostgreSQLTestContainer.execShell(shim + "sh /tmp/chart-files/" + run.getKey(), run.getValue());
            assertThat(result.getExitCode()).as(result.getStderr()).isZero();
            for (var password : run.getValue().entrySet()) {
                assertThat(result.getStdout())
                        .contains(password.getKey().replace("_PASSWORD", "_VERIFIER") + "=SCRAM-SHA-256$4096:")
                        .doesNotContain(password.getKey() + "=")
                        .doesNotContain(password.getValue());
            }
        }
    }

    private static Map<String, String> rolePasswords() {
        return Map.of(
                "DATABASE_OWNER_PASSWORD",
                        PostgreSQLTestContainer.asOwner(INSTALLED).password(),
                "DATABASE_APP_PASSWORD",
                        PostgreSQLTestContainer.asApplication(INSTALLED).password());
    }

    /** Every setting that writes a statement to the log or the statistics, for each new session of the superuser. */
    private static void recordEverything(JdbcTemplate asSuperuser, boolean on) {
        String role = PostgreSQLTestContainer.administrator().username();
        Map<String, String> settings = Map.of(
                "log_statement", "all",
                "log_min_duration_statement", "0",
                "log_transaction_sample_rate", "1",
                "debug_print_parse", "on",
                "debug_print_rewritten", "on",
                "pg_stat_statements.track_utility", "on");
        for (var setting : settings.entrySet()) {
            asSuperuser.execute(
                    on
                            ? "ALTER ROLE " + role + " SET " + setting.getKey() + " = '" + setting.getValue() + "'"
                            : "ALTER ROLE " + role + " RESET " + setting.getKey());
        }
    }

    private static String verifierOf(JdbcTemplate asSuperuser, String role) {
        return Objects.requireNonNull(
                asSuperuser.queryForObject("SELECT rolpassword FROM pg_authid WHERE rolname = ?", String.class, role));
    }

    /** A managed PostgreSQL service gives an administrator with CREATEROLE and CREATEDB, no superuser. */
    @Test
    void shouldSetUpTheRolesWhenTheAdministratorIsNoSuperuser() throws SQLException {
        String administrator = "roles_managed_admin";
        String monitorPassword = "monitor-" + UUID.randomUUID();
        try (Connection connection = connect(INSTALLED)) {
            var asSuperuser = jdbc(connection);
            recordStatements(asSuperuser);
            asSuperuser.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'roles_managed_admin') THEN
                            CREATE ROLE roles_managed_admin LOGIN CREATEROLE CREATEDB;
                        END IF;
                    END $$""");
            asSuperuser.execute("ALTER ROLE roles_managed_admin PASSWORD 'managed'");
            // As if it had created both roles: CREATE ROLE gives its creator the ADMIN option alone.
            asSuperuser.execute("GRANT hephaestus_owner, hephaestus_app TO roles_managed_admin"
                    + " WITH ADMIN TRUE, INHERIT FALSE, SET FALSE");
            asSuperuser.execute("REVOKE SET ON PARAMETER pg_stat_statements.track_utility, log_statement"
                    + " FROM roles_managed_admin");
        }
        TestDatabase postgres = new TestDatabase(
                INSTALLED.jdbcUrl().replace("hephaestus_roles_installed", "postgres"), administrator, "managed");
        try (Connection connection = connect(postgres)) {
            jdbc(connection).execute("CREATE DATABASE hephaestus_roles_managed");
        }

        // The monitor's file does not run without the quiet settings, and the quiet settings stop this
        // administrator, which may not turn off the statement statistics: no password reaches them.
        Map<String, String> monitorEnvironment = Map.of("DATABASE_MONITOR_PASSWORD", monitorPassword);
        var alone = PostgreSQLTestContainer.runChartSqlAs(
                "hephaestus_roles_managed", administrator, monitorEnvironment, List.of("database-monitor.sql"));
        assertThat(alone.getExitCode()).isNotZero();
        assertThat(alone.getStderr()).contains("run database-quiet.sql first");
        var quietMonitor = PostgreSQLTestContainer.runChartScriptAs(
                "database-monitor.sh", "hephaestus_roles_managed", administrator, monitorEnvironment);
        assertThat(quietMonitor.getExitCode()).isNotZero();
        assertThat(quietMonitor.getStderr()).contains("pg_stat_statements.track_utility is on");
        try (Connection connection = connect(INSTALLED)) {
            assertThat(recorded(jdbc(connection), monitorPassword)).isZero();
        }

        var refused = PostgreSQLTestContainer.runDatabaseRolesAs("hephaestus_roles_managed", administrator);
        assertThat(refused.getExitCode()).isNotZero();
        assertThat(refused.getStderr()).contains("pg_stat_statements.track_utility is on");

        try (Connection connection = connect(INSTALLED)) {
            jdbc(connection)
                    .execute("GRANT SET ON PARAMETER pg_stat_statements.track_utility, log_statement"
                            + " TO roles_managed_admin");
        }
        // A log of every statement, which it may not turn off, stops it too.
        try (Connection connection = connect(INSTALLED)) {
            var asSuperuser = jdbc(connection);
            asSuperuser.execute("ALTER ROLE roles_managed_admin SET log_min_duration_statement = 0");
            try {
                var logged = PostgreSQLTestContainer.runDatabaseRolesAs("hephaestus_roles_managed", administrator);
                assertThat(logged.getExitCode()).isNotZero();
                assertThat(logged.getStderr()).contains("log_min_duration_statement is 0");
            } finally {
                asSuperuser.execute("ALTER ROLE roles_managed_admin RESET log_min_duration_statement");
            }
        }

        var result = PostgreSQLTestContainer.runDatabaseRolesAs("hephaestus_roles_managed", administrator);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();

        TestDatabase database = new TestDatabase(
                INSTALLED.jdbcUrl().replace("hephaestus_roles_installed", "hephaestus_roles_managed"),
                administrator,
                "managed");
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        // A run on the migrated schema grants nothing that this administrator may not grant.
        var again = PostgreSQLTestContainer.runDatabaseRolesAs("hephaestus_roles_managed", administrator);
        assertThat(again.getExitCode()).as(again.getStderr()).isZero();
        assertThat(again.getStderr()).doesNotContain("no privileges were granted");
        try (Connection connection = connect(database)) {
            assertThat(jdbc(connection)
                            .queryForObject(
                                    "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()",
                                    String.class))
                    .isEqualTo(PostgreSQLTestContainer.OWNER_USER);
        }
        assertApplicationWorks(database);
    }

    /** The statement statistics of the whole server, readable in the installed database. */
    private static void recordStatements(JdbcTemplate asSuperuser) {
        asSuperuser.execute("CREATE SCHEMA IF NOT EXISTS monitoring");
        asSuperuser.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA monitoring");
    }

    private static long recorded(JdbcTemplate jdbc, String text) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM monitoring.pg_stat_statements WHERE strpos(query, ?) > 0", Long.class, text);
        return count == null ? 0 : count;
    }

    @Test
    void shouldStopWhenARoleHasAnAttributeThatEscapesAGrant() throws SQLException {
        try (Connection connection = connect(INSTALLED)) {
            var asSuperuser = jdbc(connection);
            asSuperuser.execute("ALTER ROLE hephaestus_app BYPASSRLS");
            try {
                assertThatThrownBy(() -> PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_installed"))
                        .hasMessageContaining("must not be superusers, bypass row-level security");
            } finally {
                asSuperuser.execute("ALTER ROLE hephaestus_app NOBYPASSRLS");
            }
        }
    }

    /** A database created before PostgreSQL 15 keeps public owned by the bootstrap superuser, and PUBLIC's CREATE. */
    @Test
    void shouldTakeCreateInPublicFromEveryRoleButTheOwnerWhenTheDatabaseIsOlderThanPostgresql15() throws SQLException {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_roles_upgraded");
        try (Connection connection = connect(database)) {
            var asSuperuser = jdbc(connection);
            asSuperuser.execute("ALTER SCHEMA public OWNER TO " + database.username());
            asSuperuser.execute("GRANT CREATE ON SCHEMA public TO PUBLIC");
        }

        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_upgraded");

        try (Connection connection = connect(database)) {
            assertThat(jdbc(connection).queryForList("""
                                    SELECT has_schema_privilege('hephaestus_app', 'public', 'CREATE') AS app,
                                           has_schema_privilege('hephaestus_owner', 'public', 'CREATE') AS owner""").getFirst().values()).containsExactly(false, true);
        }
        // The owner migrates into public, and the application role still cannot create there.
        PostgreSQLTestContainer.migrateDatabase(PostgreSQLTestContainer.asOwner(database), "dev,prod");
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            assertThatThrownBy(() -> jdbc(connection).execute("CREATE TABLE public.role_probe (id bigint)"))
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }

    /** The migrations never update an extension, and the administrator may own one, as before 1.0. */
    @Test
    void shouldUpdateAnExtensionToTheVersionOfTheImageWhenTheRolesAreSetUp() throws SQLException {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_roles_extension");
        try (Connection connection = connect(database)) {
            jdbc(connection).execute("CREATE EXTENSION citext VERSION '1.6'");
        }

        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_roles_extension");

        try (Connection connection = connect(database)) {
            assertThat(jdbc(connection).queryForObject("""
                                    SELECT e.extversion = a.default_version
                                    FROM pg_extension e JOIN pg_available_extensions a ON a.name = e.extname
                                    WHERE e.extname = 'citext'""", Boolean.class)).isTrue();
        }
    }

    private static void assertApplicationWorks(TestDatabase database) throws SQLException {
        try (Connection connection = connect(PostgreSQLTestContainer.asApplication(database))) {
            var asApplication = jdbc(connection);
            assertThat(asApplication.queryForObject("SELECT current_user", String.class))
                    .isEqualTo(PostgreSQLTestContainer.APP_USER);
            // The roles wait for the schema by reading the migration history.
            assertThat(asApplication.queryForObject("SELECT count(*) FROM databasechangelog", Long.class))
                    .isPositive();
            asApplication.execute("SELECT maintain_auth_event_partitions()");
            Long id = asApplication.queryForObject(
                    "INSERT INTO auth_event (id, event_type, result) VALUES (nextval('auth_event_id_seq'), 'LOGIN',"
                            + " 'SUCCESS') RETURNING id",
                    Long.class);
            assertThat(asApplication.queryForObject("SELECT count(*) FROM auth_event WHERE id = ?", Long.class, id))
                    .isEqualTo(1L);
        }
    }

    private static Connection connect(TestDatabase database) throws SQLException {
        return DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    private static JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
