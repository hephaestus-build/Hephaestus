package de.tum.cit.aet.hephaestus.core.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Holds the start of a process that does not migrate until the migrate run mode ({@link DatabaseMigration}) has
 * applied every change set of this release's changelog. A schema that is ahead is fine: during a rollout the
 * previous release runs against the new schema, which the expand/contract rule keeps compatible.
 *
 * <p>A {@link SpringLiquibase} whose update only waits, so Spring Boot starts JPA and JDBC users after it, as it
 * does after a migration. It reads the change log history and writes nothing.
 */
@Component
@Profile("!specs & !cds-training")
@ConditionalOnBooleanProperty(name = "spring.liquibase.enabled", havingValue = false)
@EnableConfigurationProperties(LiquibaseProperties.class)
public final class MigratedSchemaWait extends SpringLiquibase {

    private static final Logger log = LoggerFactory.getLogger(MigratedSchemaWait.class);

    private static final Duration POLL = Duration.ofSeconds(5);
    private static final Duration REPORT_EVERY = Duration.ofMinutes(1);

    private final Duration timeout;

    public MigratedSchemaWait(
            DataSource dataSource,
            LiquibaseProperties properties,
            @Value("${hephaestus.database.migration-wait}") Duration timeout) {
        this.timeout = timeout;
        setDataSource(dataSource);
        setChangeLog(properties.getChangeLog());
        if (properties.getContexts() != null) {
            setContexts(String.join(",", properties.getContexts()));
        }
        if (properties.getLabelFilter() != null) {
            setLabelFilter(String.join(",", properties.getLabelFilter()));
        }
    }

    @Override
    protected void performUpdate(Liquibase liquibase) throws LiquibaseException {
        long start = System.nanoTime();
        long nextReport = start;
        List<ChangeSet> pending = pending(liquibase);
        while (!pending.isEmpty()) {
            long now = System.nanoTime();
            if (now - start >= timeout.toNanos()) {
                throw new IllegalStateException(behind(pending) + " The wait ended after " + timeout
                        + ". Run this release's database migration, then start this process again. "
                        + "https://docs.hephaestus.build/admin/runtime-roles#database-migrations");
            }
            if (now - nextReport >= 0) {
                log.atWarn()
                        .addKeyValue("event.name", "database.migration.awaited")
                        .addKeyValue("database.migration.pending", pending.size())
                        .log("{} Waiting for the database migration", behind(pending));
                nextReport = now + REPORT_EVERY.toNanos();
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the database migration", e);
            }
            // A fresh Liquibase: the history that the previous one read is cached with it.
            try (Connection connection = getDataSource().getConnection();
                    Liquibase next = createLiquibase(connection)) {
                pending = pending(next);
            } catch (SQLException e) {
                throw new DatabaseException(e);
            }
        }
        log.atInfo().addKeyValue("event.name", "database.migration.current").log("Database schema is current");
    }

    private List<ChangeSet> pending(Liquibase liquibase) throws LiquibaseException {
        return liquibase.listUnrunChangeSets(new Contexts(getContexts()), new LabelExpression(getLabelFilter()), false);
    }

    private static String behind(List<ChangeSet> pending) {
        ChangeSet first = pending.getFirst();
        return "The database schema is behind this release: " + pending.size() + " change sets have not run, "
                + "first " + first.getId() + " in " + first.getFilePath() + ".";
    }
}
