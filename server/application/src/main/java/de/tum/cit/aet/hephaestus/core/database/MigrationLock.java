package de.tum.cit.aet.hephaestus.core.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
 * at the same time as this one.
 */
@Component
@ConditionalOnBooleanProperty(name = "spring.liquibase.enabled", matchIfMissing = true)
public class MigrationLock implements BeanPostProcessor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MigrationLock.class);

    private static final String LOCK =
            "SELECT pg_try_advisory_lock(hashtext('hephaestus'), hashtext('database-migration'))";

    private static final String UNLOCK =
            "SELECT pg_advisory_unlock(hashtext('hephaestus'), hashtext('database-migration'))";

    /**
     * How often a waiting process tries the lock again. It polls rather than blocks in {@code pg_advisory_lock}: a
     * statement waiting there holds a transaction open, and {@code CREATE INDEX CONCURRENTLY} in the running
     * migration waits for every open transaction, so the two would wait for each other.
     */
    private static final Duration RETRY = Duration.ofSeconds(1);

    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Duration timeout;
    private final boolean releasesLeftLiquibaseLock;
    private final Map<String, Connection> held = new ConcurrentHashMap<>();

    /** Reads the environment rather than {@code @Value}: the run mode's context has no placeholder configurer. */
    public MigrationLock(Environment environment) {
        this.timeout = environment.getRequiredProperty("hephaestus.database.migration-wait", Duration.class);
        this.releasesLeftLiquibaseLock = environment.acceptsProfiles(Profiles.of(DatabaseMigration.PROFILE));
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof SpringLiquibase liquibase) {
            try {
                held.put(beanName, take(liquibase));
            } catch (SQLException e) {
                throw new IllegalStateException("Could not take the database migration lock", e);
            }
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Connection connection = held.remove(beanName);
        if (connection != null) {
            release(connection);
        }
        return bean;
    }

    /**
     * A failed migration never reaches {@link #postProcessAfterInitialization}. Its context closes the pool before
     * this, which ends the session and so the lock, and the unlock then has nothing left to release.
     */
    @Override
    public void destroy() {
        held.values().forEach(connection -> {
            try {
                release(connection);
            } catch (IllegalStateException e) {
                log.debug("The database migration lock ended with its connection", e);
            }
        });
        held.clear();
    }

    private Connection take(SpringLiquibase liquibase) throws SQLException {
        String lockTable = liquibase.getDatabaseChangeLogLockTable();
        String table = (lockTable == null ? "databasechangeloglock" : lockTable).toLowerCase(Locale.ROOT);
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalStateException("The Liquibase lock table must be a plain table name: " + lockTable);
        }
        Connection connection = liquibase.getDataSource().getConnection();
        boolean locked = false;
        try {
            connection.setAutoCommit(true);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            long start = System.nanoTime();
            boolean reported = false;
            while (!Boolean.TRUE.equals(jdbc.queryForObject(LOCK, Boolean.class))) {
                if (System.nanoTime() - start >= timeout.toNanos()) {
                    throw new IllegalStateException("Another database migration still ran after " + timeout
                            + ". Wait until it ends, then start this process again. "
                            + "https://docs.hephaestus.build/admin/runtime-roles#database-migrations");
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
            if (releasesLeftLiquibaseLock
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
                    release(connection);
                } catch (IllegalStateException released) {
                    e.addSuppressed(released);
                }
            } else {
                connection.close();
            }
            throw e;
        }
    }

    /**
     * Unlocks before the connection goes back to its pool, where the session, and so the lock, would live on. A
     * connection that cannot unlock is aborted, which ends the session.
     */
    private static void release(Connection connection) {
        try {
            try (var statement = connection.createStatement()) {
                statement.execute(UNLOCK);
            }
            connection.close();
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
