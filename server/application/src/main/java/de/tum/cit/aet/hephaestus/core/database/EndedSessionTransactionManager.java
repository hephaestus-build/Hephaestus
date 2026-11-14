package de.tum.cit.aet.hephaestus.core.database;

import jakarta.persistence.EntityManagerFactory;
import java.io.Serial;
import java.sql.SQLException;
import org.hibernate.engine.spi.SessionImplementor;
import org.jspecify.annotations.Nullable;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The JPA transaction manager for a database that can end a session itself: the idle-in-transaction timeout, an
 * administrator or a failover. PostgreSQL rolls the transaction back when it ends the session, so a rollback on the
 * closed connection has nothing to do; it would only replace the failure that the caller has to see with "Connection is
 * closed".
 */
public class EndedSessionTransactionManager extends JpaTransactionManager {
    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            super.doRollback(status);
        } catch (RuntimeException rollbackFailure) {
            if (!connectionClosed(currentSession())) throw rollbackFailure;
        }
    }

    private @Nullable SessionImplementor currentSession() {
        EntityManagerFactory factory = getEntityManagerFactory();
        if (factory == null
                || !(TransactionSynchronizationManager.getResource(factory) instanceof EntityManagerHolder holder))
            return null;
        try {
            return holder.getEntityManager().unwrap(SessionImplementor.class);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * Whether the session holds a JDBC connection that is closed. A session that holds none borrows nothing here: the
     * check must not take a pooled connection while the pool may be the thing that failed.
     */
    static boolean connectionClosed(@Nullable SessionImplementor session) {
        if (session == null) return false;
        try {
            var connection = session.getJdbcCoordinator().getLogicalConnection();
            return connection.isPhysicallyConnected()
                    && connection.getPhysicalConnection().isClosed();
        } catch (SQLException | RuntimeException unreadable) {
            return false;
        }
    }
}
