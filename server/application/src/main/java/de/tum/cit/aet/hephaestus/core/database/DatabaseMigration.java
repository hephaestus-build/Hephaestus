package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.security.DatabaseTransportGuard;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.modulith.NamedInterface;

/**
 * The migrate run mode: the application image with the {@value #PROFILE} profile applies the Liquibase changelog
 * and exits, with a non-zero status when the migration fails. It starts a context of only the data source and
 * Liquibase, so it needs the database settings and no runtime role. The roles wait for it at start
 * ({@link MigratedSchemaWait}).
 *
 * <p>Not a {@code @Configuration}: the application's component scan would otherwise import this context into
 * every role.
 */
@NamedInterface("database-migration")
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, LiquibaseAutoConfiguration.class})
@Import(DatabaseTransportGuard.class)
public class DatabaseMigration {

    public static final String PROFILE = "migrate";

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigration.class);

    /**
     * Held for the whole migration on Liquibase's own connection. PostgreSQL drops a session lock with its
     * connection, so a killed migration cannot leave this lock behind, unlike Liquibase's lock table.
     */
    private static final String MIGRATION_LOCK =
            "SELECT pg_try_advisory_lock(hashtext('hephaestus'), hashtext('database-migration'))";

    /**
     * How often a second run tries the lock again. It polls rather than blocks in {@code pg_advisory_lock}: a
     * statement waiting there holds a transaction open, and {@code CREATE INDEX CONCURRENTLY} in the running
     * migration waits for every open transaction, so the two would wait for each other.
     */
    private static final Duration LOCK_RETRY = Duration.ofSeconds(1);

    private static final String MIGRATION_UNLOCK =
            "SELECT pg_advisory_unlock(hashtext('hephaestus'), hashtext('database-migration'))";

    /** Whether the active profiles, from the environment or the command line, name the run mode. */
    public static boolean requested(String[] args) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        return environment.acceptsProfiles(Profiles.of(PROFILE));
    }

    /** Migrates and returns the exit status. A failed migration throws. */
    public static int run(String[] args) {
        var application = new SpringApplication(DatabaseMigration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // Highest precedence: the role profiles turn Liquibase off, and their order must not decide this.
        String[] arguments = Stream.concat(Arrays.stream(args), Stream.of("--spring.liquibase.enabled=true"))
                .toArray(String[]::new);
        var context = application.run(arguments);
        log.atInfo().addKeyValue("event.name", "database.migration.completed").log("Database migration completed");
        return SpringApplication.exit(context);
    }

    /**
     * Makes this process the only run-mode migration, before Liquibase reads anything. Every run-mode migration
     * takes {@link #MIGRATION_LOCK} first, and under {@code prod} no role migrates at start, so a Liquibase lock held
     * now was left by a run that died, and is released. A process that migrates at start (the development monolith,
     * a release from before the run mode) takes no advisory lock; it must not run against the same database at the
     * same time.
     */
    @Bean(destroyMethod = "close")
    static MigrationLock migrationLock(
            DataSource dataSource,
            LiquibaseProperties liquibase,
            // Its check runs on creation, so taking it here checks the transport before anything connects.
            DatabaseTransportGuard transport)
            throws SQLException {
        return MigrationLock.take(dataSource, liquibase.getDatabaseChangeLogLockTable());
    }

    @Bean
    static LiquibaseAfterMigrationLock liquibaseAfterMigrationLock() {
        return new LiquibaseAfterMigrationLock();
    }

    /** Liquibase reads the change log history when it starts, so the lock must be held before. */
    static final class LiquibaseAfterMigrationLock extends AbstractDependsOnBeanFactoryPostProcessor {

        LiquibaseAfterMigrationLock() {
            super(SpringLiquibase.class, MigrationLock.class);
        }
    }

    /** The advisory lock on a connection of its own, held until the context closes. */
    static final class MigrationLock implements AutoCloseable {

        private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        private final Connection connection;

        private MigrationLock(Connection connection) {
            this.connection = connection;
        }

        static MigrationLock take(DataSource dataSource, String lockTable) throws SQLException {
            if (!TABLE.matcher(lockTable).matches()) {
                throw new IllegalStateException("The Liquibase lock table must be a plain table name: " + lockTable);
            }
            String table = lockTable.toLowerCase(Locale.ROOT);
            Connection connection = dataSource.getConnection();
            try {
                connection.setAutoCommit(true);
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                boolean reported = false;
                while (!Boolean.TRUE.equals(jdbc.queryForObject(MIGRATION_LOCK, Boolean.class))) {
                    if (!reported) {
                        log.info("Waiting for another database migration to end");
                        reported = true;
                    }
                    Thread.sleep(LOCK_RETRY);
                }
                if (Boolean.TRUE.equals(
                        jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table))) {
                    jdbc.query("SELECT lockedby, lockgranted FROM " + table + " WHERE locked", rs -> {
                        log.atWarn()
                                .addKeyValue("event.name", "database.migration.lock_released")
                                .log(
                                        "Released the Liquibase lock that {} took at {} and never released",
                                        rs.getString(1),
                                        rs.getTimestamp(2));
                    });
                    jdbc.update("UPDATE " + table
                            + " SET locked = FALSE, lockgranted = NULL, lockedby = NULL WHERE locked");
                }
                return new MigrationLock(connection);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                connection.close();
                throw new IllegalStateException("Interrupted while waiting for another database migration", e);
            } catch (SQLException | RuntimeException e) {
                connection.close();
                throw e;
            }
        }

        @Override
        public void close() throws SQLException {
            try (connection;
                    var statement = connection.createStatement()) {
                statement.execute(MIGRATION_UNLOCK);
            }
        }
    }
}
