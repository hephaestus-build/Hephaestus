package de.tum.cit.aet.hephaestus.core.database;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Scope;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.RanChangeSet;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.changelog.filter.DbmsChangeSetFilter;
import liquibase.changelog.filter.IgnoreChangeSetFilter;
import liquibase.changelog.filter.LabelChangeSetFilter;
import liquibase.database.core.PostgresDatabase;
import liquibase.integration.spring.SpringResourceAccessor;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Holds the start of a process that does not migrate until the migrate run mode ({@link DatabaseMigration}) has
 * applied every change set of this release's changelog. A schema that is ahead is fine: during a rollout the
 * previous release runs against the new schema, which the expand/contract rule keeps compatible.
 *
 * <p>It writes nothing, so it never races the migration for Liquibase's tables. It therefore reads the change log
 * history with plain SQL instead of through Liquibase, whose history service creates the change log table, and it
 * parses the changelog with no database in scope, because the parser starts that service at every include. Spring
 * Boot starts JPA and JDBC users after it, as after a migration, through {@link Detector}.
 */
@Component
@Profile("!specs & !cds-training")
@ConditionalOnBooleanProperty(name = "spring.liquibase.enabled", havingValue = false)
@EnableConfigurationProperties(LiquibaseProperties.class)
public final class MigratedSchemaWait implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MigratedSchemaWait.class);

    private static final Duration POLL = Duration.ofSeconds(5);
    private static final Duration REPORT_EVERY = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final LiquibaseProperties properties;
    private final ResourceLoader resourceLoader;
    private final Duration timeout;

    public MigratedSchemaWait(
            DataSource dataSource,
            LiquibaseProperties properties,
            ResourceLoader resourceLoader,
            @Value("${hephaestus.database.migration-wait}") Duration timeout) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.timeout = timeout;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        List<ChangeSet> expected = expected();
        long start = System.nanoTime();
        long nextReport = start;
        List<ChangeSet> pending = pending(expected);
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
            pending = pending(expected);
        }
        log.atInfo().addKeyValue("event.name", "database.migration.current").log("Database schema is current");
    }

    /** The change sets that the migration applies, with the filters Liquibase applies apart from the history. */
    private List<ChangeSet> expected() throws Exception {
        String changeLog = properties.getChangeLog();
        ResourceAccessor resources = new SpringResourceAccessor(resourceLoader);
        DatabaseChangeLog parsed = Scope.child(
                Map.of(Scope.Attr.resourceAccessor.name(), resources),
                () -> ChangeLogParserFactory.getInstance()
                        .getParser(changeLog, resources)
                        .parse(changeLog, new ChangeLogParameters(), resources));
        var contexts = new ContextChangeSetFilter(new Contexts(properties.getContexts()));
        var labels = new LabelChangeSetFilter(new LabelExpression(properties.getLabelFilter()));
        var dbms = new DbmsChangeSetFilter(new PostgresDatabase());
        var ignored = new IgnoreChangeSetFilter();
        return parsed.getChangeSets().stream()
                .filter(changeSet -> contexts.accepts(changeSet).isAccepted()
                        && labels.accepts(changeSet).isAccepted()
                        && dbms.accepts(changeSet).isAccepted()
                        && ignored.accepts(changeSet).isAccepted())
                .toList();
    }

    private List<ChangeSet> pending(List<ChangeSet> expected) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT to_regclass('databasechangelog') IS NOT NULL", Boolean.class))) {
            return expected;
        }
        List<RanChangeSet> ran = jdbc.query("SELECT filename, id, author FROM databasechangelog", (row, number) -> {
            var changeSet = new RanChangeSet();
            changeSet.setChangeLog(row.getString(1));
            changeSet.setId(row.getString(2));
            changeSet.setAuthor(row.getString(3));
            return changeSet;
        });
        return expected.stream()
                .filter(changeSet -> ran.stream().noneMatch(entry -> entry.isSameAs(changeSet)))
                .toList();
    }

    private static String behind(List<ChangeSet> pending) {
        ChangeSet first = pending.getFirst();
        return "The database schema is behind this release: " + pending.size() + " change sets have not run, "
                + "first " + first.getId() + " in " + first.getFilePath() + ".";
    }

    /** Registered in {@code META-INF/spring.factories}: the users of the database start after the wait. */
    public static final class Detector extends AbstractBeansOfTypeDatabaseInitializerDetector {

        @Override
        protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
            return Set.of(MigratedSchemaWait.class);
        }
    }
}
