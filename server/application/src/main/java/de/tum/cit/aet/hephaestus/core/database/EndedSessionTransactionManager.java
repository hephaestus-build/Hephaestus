package de.tum.cit.aet.hephaestus.core.database;

import java.io.Serial;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The JPA transaction manager for a database that can end a session itself: the idle-in-transaction timeout, an
 * administrator or a failover. PostgreSQL rolls the transaction back when it ends the session, so a rollback on the
 * closed connection has nothing to do; it would only replace the failure that the caller has to see with "Connection is
 * closed". A commit on a closed connection answers as a resource failure, which callers retry.
 */
public class EndedSessionTransactionManager extends JpaTransactionManager {
    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            super.doRollback(status);
        } catch (RuntimeException rollbackFailure) {
            if (!sessionEnded()) throw rollbackFailure;
        }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        try {
            super.doCommit(status);
        } catch (RuntimeException commitFailure) {
            if (!sessionEnded()) throw commitFailure;
            throw new DataAccessResourceFailureException(
                    "The database ended the session of this transaction", commitFailure);
        }
    }

    /** Whether the database connection of the current transaction is closed. */
    private boolean sessionEnded() {
        DataSource dataSource = getDataSource();
        if (dataSource == null
                || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder))
            return false;
        try {
            return holder.getConnection().isClosed();
        } catch (SQLException unreadable) {
            return false;
        }
    }
}
