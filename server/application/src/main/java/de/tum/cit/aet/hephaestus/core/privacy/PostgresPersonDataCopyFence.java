package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session locks cover file IO without keeping a content transaction open. Every pooled connection is
 * unlocked before it is returned. The two-integer key space is separate from the native identity locks.
 * https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS
 *
 * <p>It admits captures across runtimes only. Each content reader keeps its own workspace predicates.
 */
@Component
public class PostgresPersonDataCopyFence implements PersonDataCopyFence {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public PostgresPersonDataCopyFence(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public Lease capture() {
        return acquire(true);
    }

    @Override
    public Lease erase() {
        return acquire(false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdForCapture() {
        if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class)))
            throw new IllegalStateException("Copy admission requires a READ_COMMITTED transaction");
        waitUnbounded("SELECT pg_advisory_xact_lock_shared(2165,1)");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryHoldForCapture() {
        Boolean acquired = jdbc.queryForObject("""
                SELECT CASE WHEN current_setting('transaction_isolation')='read committed'
                    THEN pg_try_advisory_xact_lock_shared(2165,1) END
                """, Boolean.class);
        if (acquired == null) throw new IllegalStateException("Copy admission requires a READ_COMMITTED transaction");
        return acquired;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean heldForCapture() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=2165 AND objid=1
                  AND objsubid=2 AND pid=pg_backend_pid() AND granted)
                """, Boolean.class));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdForErasure() {
        waitUnbounded("SELECT pg_advisory_xact_lock(2165,1)");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdForErasureInRequest() {
        // Queue an exclusive request so later captures cannot starve an erasure.
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        try {
            jdbc.query("SELECT pg_advisory_xact_lock(2165,1)", rs -> {});
        } catch (UncategorizedSQLException failure) {
            // PostgreSQL reports lock_timeout as lock_not_available. Spring's default translator may leave it raw.
            if (failure.getCause() instanceof SQLException cause && "55P03".equals(cause.getSQLState())) {
                throw new CannotAcquireLockException("The person data copy fence is busy", failure);
            }
            throw failure;
        }
        jdbc.execute("SET LOCAL lock_timeout TO DEFAULT");
    }

    /**
     * Copies and erasures wait for each other however long the other side runs, not for the pool's lock bound. Only
     * this wait: {@code DEFAULT} is the session's value, so the rest of the transaction keeps the bound.
     */
    private void waitUnbounded(String lock) {
        jdbc.execute("SET LOCAL lock_timeout = 0");
        jdbc.query(lock, rs -> {});
        jdbc.execute("SET LOCAL lock_timeout TO DEFAULT");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean erasedSince(String store, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM person_data_request r
                    CROSS JOIN LATERAL jsonb_each(CAST(r.completed_json AS jsonb)) step
                    WHERE step.key = ? AND CAST(step.value ->> 'completedAt' AS timestamptz) >= ?)
                """, Boolean.class, store, Timestamp.from(since)));
    }

    private Lease acquire(boolean shared) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException exception) {
            throw new IllegalStateException("Copy admission is unavailable", exception);
        }
        try {
            connection.setAutoCommit(true);
            // The lease, and its receipt writes, wait for the other side to finish, not for the pool's lock bound;
            // close() restores the bound.
            try (var unbounded = connection.createStatement()) {
                unbounded.execute("SET lock_timeout = 0");
            }
            execute(connection, shared ? "pg_advisory_lock_shared" : "pg_advisory_lock");
        } catch (SQLException exception) {
            // The session may have no lock bound now: never return it to the pool.
            try {
                connection.abort(Runnable::run);
            } catch (SQLException abortFailure) {
                exception.addSuppressed(abortFailure);
            }
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Copy admission is unavailable", exception);
        }
        var receipts = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        return new Lease() {
            // One close runs the unlock.
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public JdbcOperations jdbc() {
                return receipts;
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) return;
                SQLException unlockFailure = null;
                try {
                    execute(connection, shared ? "pg_advisory_unlock_shared" : "pg_advisory_unlock");
                    try (var bounded = connection.createStatement()) {
                        bounded.execute("RESET lock_timeout");
                    }
                } catch (SQLException exception) {
                    unlockFailure = exception;
                    // Never return a session with an unknown lock state to the pool.
                    try {
                        connection.abort(Runnable::run);
                    } catch (SQLException abortFailure) {
                        exception.addSuppressed(abortFailure);
                    }
                }
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    if (unlockFailure == null) {
                        throw new IllegalStateException("Copy admission connection could not be closed", closeFailure);
                    }
                    unlockFailure.addSuppressed(closeFailure);
                }
                if (unlockFailure != null) {
                    throw new IllegalStateException("Copy admission could not be released", unlockFailure);
                }
            }
        };
    }

    private static void execute(Connection connection, String function) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + function + "(2165,1)")) {
            statement.execute();
        }
    }
}
