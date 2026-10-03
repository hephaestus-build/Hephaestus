package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
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
 */
@Component
@WorkspaceAgnostic("Cross-runtime capture admission only; each content reader retains its own workspace predicates")
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
        jdbc.query("SELECT pg_advisory_xact_lock_shared(2165,1)", rs -> {});
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdForErasure() {
        jdbc.query("SELECT pg_advisory_xact_lock(2165,1)", rs -> {});
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
            execute(connection, shared ? "pg_advisory_lock_shared" : "pg_advisory_lock");
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Copy admission is unavailable", exception);
        }
        var receipts = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        return new Lease() {
            private boolean closed;

            @Override
            public JdbcOperations jdbc() {
                return receipts;
            }

            @Override
            public synchronized void close() {
                if (closed) return;
                closed = true;
                SQLException unlockFailure = null;
                try {
                    execute(connection, shared ? "pg_advisory_unlock_shared" : "pg_advisory_unlock");
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
