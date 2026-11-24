package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.MaintenanceBarrier;
import de.tum.cit.aet.hephaestus.core.web.DocsSite;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * Lets one process at a time migrate the database. Every Liquibase migration of this release, at start or in the
 * migrate run mode ({@link DatabaseMigration}), takes this lock before Liquibase reads the change log history and
 * releases it when the migration ends. PostgreSQL drops a session lock with its connection, so a killed migration
 * cannot leave this lock behind, unlike Liquibase's lock table.
 *
 * <p>A Liquibase lock that is held while the run mode holds this lock was therefore left by a run that died, and the
 * run mode releases it. A previous release takes no advisory lock: its processes must not migrate the same database
 * at the same time as this one. The run mode also moves a database on 1.0.0-rc.1 to the 1.0 baseline under this lock
 * ({@link BaselineSynchronization}).
 *
 * <p>The lock is also the maintenance barrier ({@link MaintenanceBarrier}): while it is held, no server starts new
 * work. The baseline synchronization and Liquibase therefore run on the session that holds it, never on a session of
 * their own. If that session ends, the migration ends with it, and no change set runs after the barrier dropped.
 */
@Component
@ConditionalOnBooleanProperty(name = "spring.liquibase.enabled", matchIfMissing = true)
public class MigrationLock implements BeanPostProcessor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MigrationLock.class);

    /** Only migrations take this key: it lets one migration at a time wait for the barrier. */
    private static final String MIGRATOR =
            "SELECT pg_try_advisory_lock(hashtext('hephaestus'), hashtext('database-migrator'))";

    private static final String LOCK = "SELECT pg_try_advisory_lock(" + MaintenanceBarrier.MIGRATION_LOCK + ")";

    /**
     * Waits in PostgreSQL's lock queue. While an exclusive request waits there, every new shared request conflicts
     * with it, so no server starts new work, and the starts in flight end before the lock is granted.
     */
    private static final String LOCK_IN_TURN = "SELECT pg_advisory_lock(" + MaintenanceBarrier.MIGRATION_LOCK + ")";

    /** Both keys of this session, also when only the first was taken. */
    private static final String UNLOCK = "SELECT pg_advisory_unlock_all()";

    /**
     * How often a waiting process tries the lock again. It polls rather than blocks in {@code pg_advisory_lock}: a
     * statement waiting there holds a transaction open, and {@code CREATE INDEX CONCURRENTLY} in the running
     * migration waits for every open transaction, so the two would wait for each other.
     */
    private static final Duration RETRY = Duration.ofSeconds(1);

    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Duration timeout;
    private final boolean migrateRunMode;
    private final String migrationGuide;
    private final Map<String, Migration> held = new ConcurrentHashMap<>();

    /** A running migration: the session that holds the barrier, and the data source that Liquibase had before. */
    private record Migration(SpringLiquibase liquibase, DataSource configured, Connection session) {}

    /** Reads the environment rather than {@code @Value}: the run mode's context has no placeholder configurer. */
    public MigrationLock(Environment environment) {
        this.timeout = environment.getRequiredProperty("hephaestus.database.migration-wait", Duration.class);
        this.migrateRunMode = environment.acceptsProfiles(Profiles.of(DatabaseMigration.PROFILE));
        this.migrationGuide =
                DocsSite.page(environment.getProperty("spring.application.version", ""), DatabaseMigration.GUIDE);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof SpringLiquibase liquibase) {
            DataSource configured = liquibase.getDataSource();
            Connection session;
            try {
                session = take(configured, liquibase.getDatabaseChangeLogLockTable());
            } catch (SQLException e) {
                throw new IllegalStateException("Could not take the database migration lock", e);
            }
            held.put(beanName, new Migration(liquibase, configured, session));
            log.atInfo()
                    .addKeyValue("event.name", "database.migration.barrier_raised")
                    .log("Database migration started; no server starts new work until it ends");
            // Every statement of the migration runs on the session that holds the barrier. Liquibase's own close of
            // the connection is suppressed: the lock ends only in release(), which gives Liquibase back the
            // configured data source first.
            liquibase.setDataSource(new SingleConnectionDataSource(session, true));
            if (migrateRunMode) {
                BaselineSynchronization.run(liquibase);
            }
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Migration migration = held.remove(beanName);
        if (migration != null) {
            release(migration);
        }
        return bean;
    }

    /**
     * A failed migration never reaches {@link #postProcessAfterInitialization}, so the release here ends its session
     * and its locks. A session that already ended has nothing left to release.
     */
    @Override
    public void destroy() {
        held.values().forEach(migration -> {
            try {
                release(migration);
            } catch (IllegalStateException e) {
                log.debug("The database migration lock ended with its connection", e);
            }
        });
        held.clear();
    }

    private Connection take(DataSource dataSource, @Nullable String lockTable) throws SQLException {
        String table = (lockTable == null ? "databasechangeloglock" : lockTable).toLowerCase(Locale.ROOT);
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalStateException("The Liquibase lock table must be a plain table name: " + lockTable);
        }
        Connection connection = dataSource.getConnection();
        boolean locked = false;
        try {
            connection.setAutoCommit(true);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            long start = System.nanoTime();
            boolean reported = false;
            while (!Boolean.TRUE.equals(jdbc.queryForObject(MIGRATOR, Boolean.class))) {
                if (System.nanoTime() - start >= timeout.toNanos()) {
                    throw new IllegalStateException("Another database migration still ran after " + timeout
                            + ". Wait until it ends, then start this process again. "
                            + migrationGuide);
                }
                if (!reported) {
                    log.atWarn()
                            .addKeyValue("event.name", "database.migration.lock_awaited")
                            .log("Waiting up to {} for another database migration to end", timeout);
                    reported = true;
                }
                Thread.sleep(RETRY);
            }
            locked = true;
            if (!Boolean.TRUE.equals(jdbc.queryForObject(LOCK, Boolean.class))) {
                // Servers hold the barrier's lock shared while they start work, and a migration of a release before
                // the migrator key holds it alone. This process is the only migration of this release that waits,
                // so its open wait cannot hold up a concurrent CREATE INDEX CONCURRENTLY of this release.
                long left = Math.max(
                        1,
                        timeout.toMillis()
                                - Duration.ofNanos(System.nanoTime() - start).toMillis());
                log.atWarn()
                        .addKeyValue("event.name", "database.migration.lock_awaited")
                        .log(
                                "Waiting up to {} ms for another database migration, or for work that servers"
                                        + " started, to end",
                                left);
                jdbc.queryForObject("SELECT set_config('lock_timeout', ?, false)", String.class, left + "ms");
                try {
                    jdbc.execute(LOCK_IN_TURN);
                } catch (DataAccessException e) {
                    throw new IllegalStateException(
                            "Another database migration, or work that servers started, still ran after " + timeout
                                    + ". Find the holder as the guide says, then start this process again. "
                                    + migrationGuide,
                            e);
                } finally {
                    jdbc.execute("RESET lock_timeout");
                }
            }
            if (migrateRunMode
                    && Boolean.TRUE.equals(
                            jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table))) {
                jdbc.query("SELECT lockedby, lockgranted FROM " + table + " WHERE locked", rs -> {
                    log.atWarn()
                            .addKeyValue("event.name", "database.migration.lock_released")
                            .log(
                                    "Released the Liquibase lock that {} took at {} and never released",
                                    rs.getString(1),
                                    rs.getTimestamp(2));
                });
                jdbc.update(
                        "UPDATE " + table + " SET locked = FALSE, lockgranted = NULL, lockedby = NULL WHERE locked");
            }
            return connection;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connection.close();
            throw new IllegalStateException("Interrupted while waiting for another database migration", e);
        } catch (SQLException | RuntimeException e) {
            if (locked) {
                try {
                    release(connection, false);
                } catch (IllegalStateException released) {
                    e.addSuppressed(released);
                }
            } else {
                connection.close();
            }
            throw e;
        }
    }

    /** The session ends after Liquibase has its configured data source back, so no caller gets the closed session. */
    private static void release(Migration migration) {
        migration.liquibase().setDataSource(migration.configured());
        release(migration.session(), true);
    }

    /**
     * Unlocks before the connection goes back to its pool, where the session, and so the lock, would live on. A
     * connection that cannot unlock is aborted, which ends the session.
     */
    private static void release(Connection connection, boolean raised) {
        try {
            // Liquibase ran on this session: a transaction that a failed change set left open ends first.
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            try (var statement = connection.createStatement()) {
                statement.execute(UNLOCK);
            }
            connection.close();
            if (raised)
                log.atInfo()
                        .addKeyValue("event.name", "database.migration.barrier_lifted")
                        .log("Database migration ended; servers start new work again");
        } catch (SQLException e) {
            try {
                connection.abort(Runnable::run);
                connection.close();
            } catch (SQLException aborted) {
                e.addSuppressed(aborted);
            }
            throw new IllegalStateException("Could not release the database migration lock", e);
        }
    }
}
