package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("database")
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
    void shouldApplyEachChangeSetOnceWhenTwoRunsStartTogether() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_concurrent");

        var first = CompletableFuture.supplyAsync(() -> DatabaseMigration.run(arguments(database)));
        var second = CompletableFuture.supplyAsync(() -> DatabaseMigration.run(arguments(database)));

        assertThat(first.get(5, TimeUnit.MINUTES)).isZero();
        assertThat(second.get(5, TimeUnit.MINUTES)).isZero();
        assertThat(jdbc(database)
                        .queryForObject(
                                "SELECT count(*) - count(DISTINCT (id, author, filename)) FROM databasechangelog",
                                Long.class))
                .isZero();
    }

    @Test
    void shouldApplyEachChangeSetOnceWhenAProcessMigratesAtStartBesideTheRunMode() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_migrate_beside_start");

        var atStart = CompletableFuture.runAsync(() -> migrateAtStart(database));
        var runMode = CompletableFuture.supplyAsync(() -> DatabaseMigration.run(arguments(database)));

        atStart.get(5, TimeUnit.MINUTES);
        assertThat(runMode.get(5, TimeUnit.MINUTES)).isZero();
        assertThat(jdbc(database)
                        .queryForObject(
                                "SELECT count(*) - count(DISTINCT (id, author, filename)) FROM databasechangelog",
                                Long.class))
                .isZero();
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
                    "--DATABASE_URL=postgresql://db.example.org:5432/hephaestus?sslmode=disable",
                    "--DATABASE_USERNAME=hephaestus",
                    "--DATABASE_PASSWORD=unused",
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
        // The production profile and only the settings the Compose migration service is given.
        return new String[] {
            "--spring.profiles.active=prod," + DatabaseMigration.PROFILE,
            "--DATABASE_URL=" + database.jdbcUrl().substring("jdbc:".length()),
            "--DATABASE_USERNAME=" + database.username(),
            "--DATABASE_PASSWORD=" + database.password(),
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
        var wait = new MigratedSchemaWait(dataSource(database), properties, timeout);
        wait.setResourceLoader(new DefaultResourceLoader());
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
