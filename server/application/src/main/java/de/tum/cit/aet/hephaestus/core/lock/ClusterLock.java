package de.tum.cit.aet.hephaestus.core.lock;

import java.io.Serial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes work by name across every server process, for work that calls a provider or the broker and so must not
 * hold a transaction (docs/contributor/transactions.md). An in-memory lock serializes only one process.
 *
 * <p>The lock is a PostgreSQL session lock on a connection of its own, without a transaction. PostgreSQL drops it with
 * the connection, so a process that dies leaves no lock behind. A waiting caller polls rather than blocks in
 * {@code pg_advisory_lock}: a statement that waits there holds a transaction open, and a migration's
 * {@code CREATE INDEX CONCURRENTLY} waits for every open transaction. Names hash to 32 bits, so two names can share a
 * lock: that costs only a wait, never a missed one.
 */
@Component
public class ClusterLock {

    private static final Logger log = LoggerFactory.getLogger(ClusterLock.class);

    private static final String LOCK = "SELECT pg_try_advisory_lock(hashtext('hephaestus-cluster-lock'), hashtext(?))";

    private static final String UNLOCK = "SELECT pg_advisory_unlock(hashtext('hephaestus-cluster-lock'), hashtext(?))";

    private static final Duration RETRY = Duration.ofMillis(200);

    private final DataSource dataSource;

    public ClusterLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs {@code work} while this process holds the lock {@code name}.
     *
     * @param wait how long to wait while another process holds the lock; zero tries once
     * @return the work's result, or empty without running the work when another process held the lock for all of
     *     {@code wait}
     * @throws UnavailableException when the database cannot take or report the lock
     */
    public <T> Optional<T> call(String name, Duration wait, Supplier<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            if (!take(connection, name, wait)) return Optional.empty();
            try {
                return Optional.of(work.get());
            } finally {
                release(connection, name);
            }
        } catch (SQLException failure) {
            throw new UnavailableException(failure);
        }
    }

    /** {@link #call} for work without a result; false when the work did not run. */
    public boolean run(String name, Duration wait, Runnable work) {
        return call(name, wait, () -> {
                    work.run();
                    return Boolean.TRUE;
                })
                .isPresent();
    }

    private static boolean take(Connection connection, String name, Duration wait) throws SQLException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (!query(connection, LOCK, name)) {
            if (System.nanoTime() >= deadline) return false;
            try {
                TimeUnit.MILLISECONDS.sleep(RETRY.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** A pooled connection that may still hold the lock must not return to the pool, so a failed unlock aborts it. */
    private static void release(Connection connection, String name) {
        try {
            if (query(connection, UNLOCK, name)) return;
        } catch (SQLException failure) {
            log.warn(
                    "Cluster lock release failed: error.type={}",
                    failure.getClass().getName());
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException failure) {
            log.warn(
                    "Cluster lock connection abort failed: error.type={}",
                    failure.getClass().getName());
        }
    }

    private static boolean query(Connection connection, String sql, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** The database could not take or report the lock; the caller answers as if the work could not run now. */
    public static final class UnavailableException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        UnavailableException(Throwable cause) {
            super("The cluster lock is unavailable", cause);
        }
    }
}
