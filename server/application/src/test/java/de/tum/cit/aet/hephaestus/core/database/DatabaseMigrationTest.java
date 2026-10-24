package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

        assertThat(start).failsWithin(Duration.ofSeconds(8)).withThrowableThat().isInstanceOf(TimeoutException.class);
        assertThat(DatabaseMigration.run(arguments(database))).isZero();

        start.get(1, TimeUnit.MINUTES);
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
