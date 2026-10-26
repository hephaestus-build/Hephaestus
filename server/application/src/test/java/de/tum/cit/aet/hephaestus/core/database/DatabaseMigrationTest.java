package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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
                    .until(() -> output.getOut().contains("for another database migration to end"));

            assertThat(jdbc.queryForObject("SELECT locked FROM databasechangeloglock WHERE id = 1", Boolean.class))
                    .isTrue();
        }

        // The live run is gone, and with its session its migration lock; the Liquibase lock it left is stale now.
        assertThat(waiting.get(5, TimeUnit.MINUTES)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked FROM databasechangeloglock WHERE id = 1", Boolean.class))
                .isFalse();
    }

    @Test
    void shouldStopAProcessThatMigratesAtStartWhenAnotherMigrationHoldsTheLock() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_lock_held");
        try (Connection holder = dataSource(database).getConnection();
                var statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(hashtext('hephaestus'), hashtext('database-migration'))");

            assertThatThrownBy(() -> migrateAtStart(database, "--hephaestus.database.migration-wait=1s"))
                    .rootCause()
                    .hasMessageContaining("Another database migration still ran after PT1S");
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
        assertThat(DatabaseMigration.requested(new String[] {"--spring.profiles.active=prod,worker"}))
                .isFalse();
        assertThat(DatabaseMigration.requested(new String[0])).isFalse();
    }

    private static String[] arguments(TestDatabase database) {
        // The production profile and only the data source. On the command line, it outranks the SPRING_DATASOURCE_*
        // that the database job exports for its own PostgreSQL. The boot smoke covers DATABASE_*.
        return new String[] {
            "--spring.profiles.active=prod," + DatabaseMigration.PROFILE,
            "--spring.datasource.url=" + database.jdbcUrl(),
            "--spring.datasource.username=" + database.username(),
            "--spring.datasource.password=" + database.password(),
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
                                "--spring.liquibase.enabled=true",
                                // The run mode's contexts: a database migrated under another holds other change sets.
                                "--spring.liquibase.contexts=prod",
                                "--spring.main.banner-mode=off"),
                        Arrays.stream(settings))
                .toArray(String[]::new);
        application.run(arguments).close();
    }

    private static void awaitSchema(TestDatabase database, Duration timeout) {
        var properties = new LiquibaseProperties();
        properties.setChangeLog("classpath:db/master.xml");
        properties.setContexts(List.of("prod"));
        var wait = new MigratedSchemaWait(dataSource(database), properties, new DefaultResourceLoader(), timeout);
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
