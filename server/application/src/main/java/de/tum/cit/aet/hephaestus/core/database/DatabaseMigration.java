package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.security.DatabaseTransportGuard;
import java.util.Arrays;
import java.util.stream.Stream;
import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
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
 * ({@link MigratedSchemaWait}). {@link MigrationLock} keeps a second migration out.
 *
 * <p>Not a {@code @Configuration}: the application's component scan would otherwise import this context into
 * every role.
 */
@NamedInterface("database-migration")
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, LiquibaseAutoConfiguration.class})
@Import({DatabaseTransportGuard.class, MigrationLock.class})
public class DatabaseMigration {

    public static final String PROFILE = "migrate";

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigration.class);

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

    @Bean
    static LiquibaseAfterTransportCheck liquibaseAfterTransportCheck() {
        return new LiquibaseAfterTransportCheck();
    }

    /** The transport check runs on creation, so it must run before Liquibase connects. */
    static final class LiquibaseAfterTransportCheck extends AbstractDependsOnBeanFactoryPostProcessor {

        LiquibaseAfterTransportCheck() {
            super(SpringLiquibase.class, DatabaseTransportGuard.class);
        }
    }
}
