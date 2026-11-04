package de.tum.cit.aet.hephaestus.core.database;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.integration.spring.SpringResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Moves a database on 1.0.0-rc.1 to the 1.0 baseline ({@code db/changelog/1.0.0/}), the one schema that a new database
 * also starts from. The baseline never runs on such a database. The migrate run records its change sets as run, without
 * DDL, only when the schema equals the pinned fingerprint of the baseline ({@code db/fingerprint/}). Any other schema is
 * refused, and the database stays as it was. Its older history rows stay too: the previous release reads them on a
 * rollback, and Liquibase ignores them.
 *
 * <p>A later {@code next/} change set means that a build past 1.0.0-rc.1 ran on this database. The pin describes
 * 1.0.0-rc.1 alone, so it cannot judge that schema, and the database is refused. A database with no 1.0.0-rc.1 history
 * is left to the guard in {@code db/master.xml}, which refuses it.
 */
final class BaselineSynchronization {

    private static final Logger log = LoggerFactory.getLogger(BaselineSynchronization.class);

    private static final String TAG = "1.0.0-baseline";
    private static final String AUTHOR = "hephaestus";
    private static final String BASELINE_FILE = "db/changelog/1.0.0/baseline.xml";
    private static final String BASELINE_LAST = "1.0.0-tag";
    private static final String RC1_LAST_FILE = "db/changelog/next/20261101103000-erasure-history-redaction.xml";
    private static final String RC1_LAST = "1793504565891-1";

    /** The most differences that the refusal names; the count says when there are more. */
    private static final int REPORTED = 200;

    private BaselineSynchronization() {}

    static void run(SpringLiquibase liquibase) {
        DataSource dataSource = Objects.requireNonNull(liquibase.getDataSource(), "the migration has a data source");
        var history = new JdbcTemplate(dataSource);
        if (!Boolean.TRUE.equals(
                        history.queryForObject("SELECT to_regclass('databasechangelog') IS NOT NULL", Boolean.class))
                || ran(history, BASELINE_LAST, BASELINE_FILE)
                || !ran(history, RC1_LAST, RC1_LAST_FILE)) {
            return;
        }
        List<String> later =
                history.query("""
                SELECT DISTINCT filename FROM databasechangelog
                WHERE filename LIKE 'db/changelog/next/%' AND filename COLLATE "C" > ?
                ORDER BY 1
                """, (row, number) -> Objects.requireNonNull(row.getString(1)), RC1_LAST_FILE);
        if (!later.isEmpty()) {
            throw refused(
                    "database.migration.baseline_refused_later_build",
                    "This database ran a build later than 1.0.0-rc.1 before it had the 1.0 baseline: "
                            + String.join(", ", later)
                            + ". The upgrade changed nothing. Restore the backup from 1.0.0-rc.1 and upgrade from there,"
                            + " or contact the maintainers.");
        }
        try {
            String context = "prod".equals(liquibase.getContexts()) ? "prod" : "dev";
            List<String> pinned = pinned(context);
            List<String> found;
            try (Connection connection = dataSource.getConnection()) {
                // An extension that an operator added, such as pg_stat_statements, is not part of the schema.
                found = SchemaFingerprint.of(connection).stream()
                        .filter(line -> !line.startsWith("extension|") || pinned.contains(line))
                        .toList();
            }
            if (!found.equals(pinned)) {
                List<String> difference =
                        SchemaFingerprint.difference(pinned, found).lines().toList();
                throw refused(
                        "database.migration.baseline_refused_schema",
                        "This database's schema differs from 1.0.0-rc.1 in " + difference.size() + " places. The"
                                + " upgrade changed nothing, so the current release can keep running. Contact the"
                                + " maintainers with this list. A minus sign is what 1.0.0-rc.1 has, a plus sign is what"
                                + " this database has:\n"
                                + String.join("\n", difference.subList(0, Math.min(REPORTED, difference.size()))));
            }
            synchronize(liquibase, dataSource);
        } catch (SQLException | LiquibaseException e) {
            throw new IllegalStateException("Could not move the database to the 1.0 baseline", e);
        }
        log.atInfo()
                .addKeyValue("event.name", "database.migration.baseline_synchronized")
                .log("Recorded the 1.0 baseline on a database whose schema equals 1.0.0-rc.1");
    }

    private static boolean ran(JdbcTemplate history, String id, String file) {
        return Boolean.TRUE.equals(history.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM databasechangelog WHERE id = ? AND author = ? AND filename = ?)",
                Boolean.class,
                id,
                AUTHOR,
                file));
    }

    private static void synchronize(SpringLiquibase settings, DataSource dataSource)
            throws SQLException, LiquibaseException {
        var resourceLoader = Objects.requireNonNull(settings.getResourceLoader(), "the migration loads resources");
        try (var migration = new Liquibase(
                settings.getChangeLog(),
                new SpringResourceAccessor(resourceLoader),
                DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection())))) {
            migration.changeLogSync(
                    TAG, new Contexts(settings.getContexts()), new LabelExpression(settings.getLabelFilter()));
        } catch (LiquibaseException | SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not close the Liquibase session", e);
        }
    }

    /** Logs name an exception by its class only, so the operator reads the refusal in this line. */
    private static IllegalStateException refused(String event, String message) {
        log.atError().addKeyValue("event.name", event).log(message);
        return new IllegalStateException(message);
    }

    private static List<String> pinned(String context) {
        try (InputStream in = Objects.requireNonNull(
                BaselineSynchronization.class.getResourceAsStream("/db/fingerprint/baseline-" + context + ".txt"),
                "the fingerprint of the baseline is on the classpath")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
