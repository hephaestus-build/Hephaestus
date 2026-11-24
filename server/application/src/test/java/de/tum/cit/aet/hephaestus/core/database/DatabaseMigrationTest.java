package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepAdmission;
import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.TestDatabaseCleanup;
import de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets;
import java.sql.Connection;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("database")
@ExtendWith(OutputCaptureExtension.class)
class DatabaseMigrationTest {

    @Test
    void shouldResumeAnUnrecordedSweepKeyChangeAndRestoreTheMissingSigningCopy() {
        var database = TestDatabaseCleanup.createDatabase("hephaestus_migrate_sweep_key");
        PostgreSQLTestContainer.applyDatabaseRoles("hephaestus_migrate_sweep_key");
        var owner = PostgreSQLTestContainer.asOwner(database);
        assertThat(DatabaseMigration.run(arguments(owner))).isZero();
        var jdbc = jdbc(owner);
        assertThat(jdbc.update("DELETE FROM databasechangelog WHERE id = '1795540920000-1'"))
                .isEqualTo(1);
        var app = jdbc(PostgreSQLTestContainer.asApplication(database));
        assertThat(app.update("DELETE FROM system_secret WHERE name = 'owner-sweep-admission'"))
                .isEqualTo(1);
        assertThat(hasSweepSigningKey(app)).isFalse();

        assertThat(DatabaseMigration.run(arguments(owner))).isZero();

        assertThat(hasSweepSigningKey(app)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lifecycle_private.sweep_key", Long.class))
                .isEqualTo(1);
    }

    @Test
    void shouldLeaveRotationToTheServerAndRefuseRepairWithAnIncompatibleSystemKey() {
        var database = TestDatabaseCleanup.createDatabase("hephaestus_migrate_wrong_sweep_key");
        assertThat(DatabaseMigration.run(arguments(database))).isZero();
        var jdbc = jdbc(database);
        var secrets = TestSystemSecrets.systemSecrets(TestSystemSecrets.DEFAULT_KEY);
        jdbc.update(
                "INSERT INTO system_secret (name, sealed_value) VALUES ('existing', ?)",
                secrets.seal(new byte[32], SecretBinding.of("system_secret.sealed_value", "existing")));

        String newKey = "new-encryption-key-32-byte-value";
        var newSettings = Stream.concat(
                        Arrays.stream(arguments(database))
                                .filter(arg -> !arg.startsWith("--hephaestus.security.encryption-key=")),
                        Stream.of("--hephaestus.security.encryption-key=" + newKey))
                .toArray(String[]::new);
        var original = jdbc.queryForObject(
                "SELECT sealed_value FROM system_secret WHERE name = 'owner-sweep-admission'", byte[].class);
        assertThat(DatabaseMigration.run(newSettings)).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT sealed_value = ? FROM system_secret WHERE name = 'owner-sweep-admission'
                        """, Boolean.class, original)).isTrue();

        assertThat(jdbc.update("DELETE FROM system_secret WHERE name = 'owner-sweep-admission'"))
                .isEqualTo(1);

        assertThatThrownBy(() -> DatabaseMigration.run(newSettings))
                .hasStackTraceContaining("The configured system key does not open existing values");

        var activeSecrets = TestSystemSecrets.systemSecrets(newKey);
        jdbc.update(
                "INSERT INTO system_secret (name, sealed_value) VALUES ('current', ?)",
                activeSecrets.seal(new byte[32], SecretBinding.of("system_secret.sealed_value", "current")));
        assertThat(DatabaseMigration.run(Stream.concat(
                                Arrays.stream(newSettings),
                                Stream.of(
                                        "--hephaestus.security.prior-encryption-key=" + TestSystemSecrets.DEFAULT_KEY))
                        .toArray(String[]::new)))
                .isZero();
        assertThat(hasSweepSigningKey(jdbc)).isTrue();
        var replacement = jdbc.queryForObject(
                "SELECT sealed_value FROM system_secret WHERE name = 'owner-sweep-admission'", byte[].class);
        assertThat(activeSecrets.isSealedWithActiveKey(Objects.requireNonNull(replacement)))
                .isTrue();
    }

    @Test
    void shouldApplyTheChangelogAndExitWhenTheMigrateProfileRuns() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_fresh");

        assertThat(DatabaseMigration.run(arguments(database))).isZero();

        // A process that does not migrate now starts without waiting.
        CompletableFuture.runAsync(() -> awaitSchema(database, Duration.ofSeconds(1)))
                .get(1, TimeUnit.MINUTES);
        assertThat(DatabaseMigration.run(arguments(database))).isZero();
    }

    @Test
    void shouldReleaseTheLiquibaseLockWhenAKilledMigrationLeftIt() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_stale_lock");
        assertThat(DatabaseMigration.run(arguments(database))).isZero();
        var jdbc = jdbc(database);
        jdbc.update("UPDATE databasechangeloglock SET locked = TRUE, lockgranted = now(), lockedby = 'killed job'");

        assertThat(DatabaseMigration.run(arguments(database))).isZero();

        assertThat(jdbc.queryForObject("SELECT locked FROM databasechangeloglock WHERE id = 1", Boolean.class))
                .isFalse();
    }

    @Test
    void shouldHoldTheStartUntilTheMigrationHasRun() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_awaited");
        var start = CompletableFuture.runAsync(() -> awaitSchema(database, Duration.ofMinutes(5)));

        assertThat(DatabaseMigration.run(arguments(database))).isZero();

        start.get(1, TimeUnit.MINUTES);
    }

    @Test
    void shouldLeaveAFreshDatabaseToTheMigrationWhileTheStartWaits() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_untouched");

        assertThatThrownBy(() -> awaitSchema(database, Duration.ofSeconds(1)))
                .rootCause()
                .hasMessageContaining("The database schema is behind this release");

        // A table that the wait created would make the migration fail to create it.
        assertThat(jdbc(database)
                        .queryForObject(
                                "SELECT count(*) FROM pg_tables WHERE schemaname = current_schema()", Integer.class))
                .isZero();
        assertThat(DatabaseMigration.run(arguments(database))).isZero();
        awaitSchema(database, Duration.ofSeconds(1));
    }

    @Test
    void shouldNameTheChangeSetThatAPartlyMigratedHistoryLacks() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_partial");
        assertThat(DatabaseMigration.run(arguments(database))).isZero();
        var jdbc = jdbc(database);
        String id = jdbc.queryForObject(
                "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1", String.class);
        jdbc.update("DELETE FROM databasechangelog WHERE id = ?", id);

        assertThatThrownBy(() -> awaitSchema(database, Duration.ofSeconds(1)))
                .rootCause()
                .hasMessageContaining("1 change sets have not run, first " + id + " in db/changelog/");
    }

    @Test
    void shouldStartWhenTheSchemaIsAheadOfThisRelease() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_ahead");
        assertThat(DatabaseMigration.run(arguments(database))).isZero();
        // A change set of a later release, which this release's changelog does not hold.
        jdbc(database).update("""
                        INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype)
                        SELECT 'later-release', 'hephaestus', 'db/changelog/9999999999999_changelog.xml', now(),
                               max(orderexecuted) + 1, 'EXECUTED'
                        FROM databasechangelog
                        """);

        awaitSchema(database, Duration.ofSeconds(1));
    }

    @Test
    void shouldKeepAnotherRunsLiquibaseLockWhileThatRunHoldsTheMigrationLock(CapturedOutput output) throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_live_lock");
        assertThat(DatabaseMigration.run(arguments(database))).isZero();
        var jdbc = jdbc(database);
        CompletableFuture<Integer> waiting;
        try (Connection holder = dataSource(database).getConnection();
                var statement = holder.createStatement()) {
            // Another live migration: it holds the migration lock and the Liquibase lock.
            statement.execute("SELECT pg_advisory_lock(hashtext('hephaestus'), hashtext('database-migration'))");
            jdbc.update("UPDATE databasechangeloglock SET locked = TRUE, lockgranted = now(), lockedby = 'live run'");

            waiting = CompletableFuture.supplyAsync(() -> DatabaseMigration.run(arguments(database)));
            await().atMost(Duration.ofMinutes(1))
                    .until(() -> output.getOut().contains("database.migration.lock_awaited"));

            assertThat(jdbc.queryForObject("SELECT locked FROM databasechangeloglock WHERE id = 1", Boolean.class))
                    .isTrue();
        }

        // The live run is gone, and with its session its migration lock; the Liquibase lock it left is stale now.
        assertThat(waiting.get(5, TimeUnit.MINUTES)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked FROM databasechangeloglock WHERE id = 1", Boolean.class))
                .isFalse();
    }

    /**
     * The maintenance barrier is the migration lock, so it must end only when the migration ends. A change set that
     * ran on a session of its own would go on after the lock's session ended, and servers would start work during it.
     */
    @Test
    void shouldRunEachChangeSetOnTheSessionThatHoldsTheBarrierAndEndWithIt() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_barrier_session");
        var jdbc = jdbc(database);
        CompletableFuture<Integer> migration;
        try (Connection test = dataSource(database).getConnection();
                var statement = test.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(4242)");
            migration = CompletableFuture.supplyAsync(() -> DatabaseMigration.run(Stream.concat(
                            Arrays.stream(arguments(database)),
                            Stream.of(
                                    "--spring.liquibase.change-log=classpath:db/migration-fixture/stalled-change-set.xml"))
                    .toArray(String[]::new)));
            Integer stalled = await().atMost(Duration.ofMinutes(1))
                    .until(
                            () -> jdbc
                                    .queryForList(
                                            "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND objid = 4242"
                                                    + " AND NOT granted",
                                            Integer.class)
                                    .stream()
                                    .findFirst()
                                    .orElse(null),
                            pid -> pid != null);
            Integer barrier = jdbc.queryForObject(
                    "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND granted AND objsubid = 2"
                            + " AND classid = hashtext('hephaestus')::oid AND objid = hashtext('database-migration')::oid",
                    Integer.class);
            assertThat(barrier)
                    .as("the session that holds the barrier runs the change set")
                    .isEqualTo(stalled);

            // The barrier's session ends while the change set runs, as a terminated or timed-out session does.
            assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, barrier))
                    .isTrue();
        }

        assertThatThrownBy(() -> migration.get(2, TimeUnit.MINUTES))
                .as("the migration ends with the barrier's session")
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = 'stalled'", Integer.class))
                .isZero();
    }

    /**
     * A migration waits in PostgreSQL's queue for the work that servers started, and no server starts new work while
     * it waits. A migration that only polled would let new starts slip in ahead of it until its wait ended.
     */
    @Test
    void shouldKeepNewWorkOutWhileTheMigrationWaitsForWorkThatServersStarted() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_barrier_queue");
        var jdbc = jdbc(database);
        CompletableFuture<Integer> migration;
        try (Connection start = dataSource(database).getConnection();
                var statement = start.createStatement()) {
            // A server that starts work holds the barrier's lock shared for the start's steps.
            statement.execute("SELECT pg_advisory_lock_shared(hashtext('hephaestus'), hashtext('database-migration'))");
            migration = CompletableFuture.supplyAsync(() -> DatabaseMigration.run(Stream.concat(
                            Arrays.stream(arguments(database)),
                            Stream.of(
                                    "--spring.liquibase.change-log=classpath:db/migration-fixture/quick-change-set.xml"))
                    .toArray(String[]::new)));
            // In PostgreSQL's queue: the exclusive request waits, which the log line only announces.
            await().atMost(Duration.ofMinutes(1))
                    .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
                                    + " AND mode = 'ExclusiveLock' AND objsubid = 2"
                                    + " AND objid = hashtext('database-migration')::oid)",
                            Boolean.class)));

            assertThat(jdbc.queryForObject(
                            "SELECT pg_try_advisory_xact_lock_shared(hashtext('hephaestus'),"
                                    + " hashtext('database-migration'))",
                            Boolean.class))
                    .as("a new start is refused while the migration waits")
                    .isFalse();
            assertThat(migration).isNotDone();
        }

        assertThat(migration.get(2, TimeUnit.MINUTES)).isZero();
    }

    @Test
    void shouldLiftTheBarrierWhenAChangeSetFailsOnALiveSession() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_barrier_failure");

        assertThatThrownBy(() -> DatabaseMigration.run(Stream.concat(
                                Arrays.stream(arguments(database)),
                                Stream.of(
                                        "--spring.liquibase.change-log=classpath:db/migration-fixture/failing-change-set.xml"))
                        .toArray(String[]::new)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc(database)
                        .queryForObject(
                                "SELECT pg_try_advisory_xact_lock_shared(hashtext('hephaestus'),"
                                        + " hashtext('database-migration'))",
                                Boolean.class))
                .as("servers start work again")
                .isTrue();
    }

    @Test
    void shouldStopAProcessThatMigratesAtStartWhenAnotherMigrationHoldsTheLock() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_lock_held");
        try (Connection holder = dataSource(database).getConnection();
                var statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(hashtext('hephaestus'), hashtext('database-migration'))");

            assertThatThrownBy(() -> migrateAtStart(database, "--hephaestus.database.migration-wait=1s"))
                    .hasStackTraceContaining("still ran after PT1S");
        }
        assertThat(jdbc(database).queryForObject("SELECT to_regclass('databasechangelog') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void shouldNameThePendingChangeSetWhenTheMigrationNeverRuns() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_missing");

        assertThatThrownBy(() -> awaitSchema(database, Duration.ofSeconds(1)))
                .rootCause()
                .hasMessageContaining("The database schema is behind this release")
                .hasMessageContaining("db/changelog/")
                .hasMessageContaining("database migration");
    }

    @Test
    void shouldRefusePlaintextToARemoteDatabaseBeforeMigrating() {
        assertThatThrownBy(() -> DatabaseMigration.run(new String[] {
                    "--spring.profiles.active=prod," + DatabaseMigration.PROFILE,
                    "--spring.datasource.url=jdbc:postgresql://db.example.org:5432/hephaestus?sslmode=disable",
                    "--spring.datasource.username=hephaestus",
                    "--spring.datasource.password=unused",
                    "--spring.main.banner-mode=off"
                }))
                .rootCause()
                .hasMessageContaining("must use TLS");
    }

    @Test
    void shouldSelectTheRunModeOnlyWhenTheMigrateProfileIsActive() {
        assertThat(DatabaseMigration.requested(new String[] {"--spring.profiles.active=prod,migrate"}))
                .isTrue();
        assertThat(DatabaseMigration.requested(new String[] {"--spring.profiles.active=prod,webhook"}))
                .isFalse();
        assertThat(DatabaseMigration.requested(new String[0])).isFalse();
    }

    private static boolean hasSweepSigningKey(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT FROM public.system_secret WHERE name = ?)",
                Boolean.class,
                RowSweepAdmission.KEY_NAME));
    }

    private static String[] arguments(TestDatabase database) {
        // The production profile and only the data source. On the command line, it outranks the SPRING_DATASOURCE_*
        // and SPRING_LIQUIBASE_* that the database job exports for its own PostgreSQL. The boot smoke covers
        // DATABASE_*.
        return new String[] {
            "--spring.profiles.active=prod," + DatabaseMigration.PROFILE,
            "--spring.datasource.url=" + database.jdbcUrl(),
            "--spring.datasource.username=" + database.username(),
            "--spring.datasource.password=" + database.password(),
            "--spring.liquibase.user=" + database.username(),
            "--spring.liquibase.password=" + database.password(),
            "--hephaestus.security.encryption-key=" + TestSystemSecrets.DEFAULT_KEY,
            "--spring.main.banner-mode=off"
        };
    }

    /** A process that migrates at start, as the development server does, with Liquibase outside the run mode. */
    private static void migrateAtStart(TestDatabase database, String... settings) {
        var application = new SpringApplication(DatabaseMigration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        String[] arguments = Stream.concat(
                        Stream.of(
                                "--spring.datasource.url=" + database.jdbcUrl(),
                                "--spring.datasource.username=" + database.username(),
                                "--spring.datasource.password=" + database.password(),
                                "--spring.liquibase.user=" + database.username(),
                                "--spring.liquibase.password=" + database.password(),
                                "--spring.liquibase.enabled=true",
                                // The run mode's contexts: a database migrated under another holds other change sets.
                                "--spring.liquibase.contexts=prod",
                                "--hephaestus.security.encryption-key=" + TestSystemSecrets.DEFAULT_KEY,
                                "--spring.main.banner-mode=off"),
                        Arrays.stream(settings))
                .toArray(String[]::new);
        application.run(arguments).close();
    }

    private static void awaitSchema(TestDatabase database, Duration timeout) {
        var properties = new LiquibaseProperties();
        properties.setChangeLog("classpath:db/master.xml");
        properties.setContexts(List.of("prod"));
        var wait =
                new MigratedSchemaWait(dataSource(database), properties, new DefaultResourceLoader(), timeout, "1.0.0");
        try {
            wait.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DriverManagerDataSource dataSource(TestDatabase database) {
        return new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
    }

    private static JdbcTemplate jdbc(TestDatabase database) {
        return new JdbcTemplate(dataSource(database));
    }
}
