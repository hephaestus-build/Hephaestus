package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.security.DatabaseTransportGuard;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Stream;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.Customizer;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
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
            "SELECT pg_advisory_lock(hashtext('hephaestus'), hashtext('database-migration'))";

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
     * Makes this process the only migration. While it holds {@link #MIGRATION_LOCK}, no other migration runs, so
     * a held Liquibase lock was left by a migration that died, and is released.
     */
    @Bean
    static Customizer<Liquibase> oneMigrationAtATime(
            // Its check runs on creation, so taking it here checks the transport before Liquibase connects.
            DatabaseTransportGuard transport) {
        return liquibase -> {
            var database = liquibase.getDatabase();
            try (Statement statement = ((JdbcConnection) database.getConnection())
                    .getUnderlyingConnection()
                    .createStatement()) {
                statement.execute(MIGRATION_LOCK);
                LockService locks = LockServiceFactory.getInstance().getLockService(database);
                DatabaseChangeLogLock[] left = locks.listLocks();
                if (left.length > 0) {
                    locks.forceReleaseLock();
                    log.atWarn()
                            .addKeyValue("event.name", "database.migration.lock_released")
                            .log(
                                    "Released the Liquibase lock that {} took at {} and never released",
                                    left[0].getLockedBy(),
                                    left[0].getLockGranted().toInstant());
                }
            } catch (SQLException | LiquibaseException e) {
                throw new IllegalStateException("Could not take the database migration lock", e);
            }
        };
    }
}
