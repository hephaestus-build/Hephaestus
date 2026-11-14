package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.sql.Connection;
import java.sql.SQLException;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.resource.jdbc.spi.LogicalConnectionImplementor;
import org.junit.jupiter.api.Test;

/** Only a closed connection that the session already holds lets a rollback failure go; every other one propagates. */
class EndedSessionTransactionManagerTest extends BaseUnitTest {
    private final SessionImplementor session = mock(SessionImplementor.class);
    private final LogicalConnectionImplementor logical = mock(LogicalConnectionImplementor.class);
    private final Connection physical = mock(Connection.class);

    EndedSessionTransactionManagerTest() {
        var coordinator = mock(JdbcCoordinator.class);
        when(session.getJdbcCoordinator()).thenReturn(coordinator);
        when(coordinator.getLogicalConnection()).thenReturn(logical);
    }

    @Test
    void shouldTreatAClosedHeldConnectionAsAnEndedSession() throws SQLException {
        when(logical.isPhysicallyConnected()).thenReturn(true);
        when(logical.getPhysicalConnection()).thenReturn(physical);
        when(physical.isClosed()).thenReturn(true);

        assertThat(EndedSessionTransactionManager.connectionClosed(session)).isTrue();
    }

    @Test
    void shouldKeepARollbackFailureOnAnOpenConnection() throws SQLException {
        when(logical.isPhysicallyConnected()).thenReturn(true);
        when(logical.getPhysicalConnection()).thenReturn(physical);
        when(physical.isClosed()).thenReturn(false);

        assertThat(EndedSessionTransactionManager.connectionClosed(session)).isFalse();
    }

    @Test
    void shouldNotBorrowAConnectionWhenTheSessionHoldsNone() {
        when(logical.isPhysicallyConnected()).thenReturn(false);

        assertThat(EndedSessionTransactionManager.connectionClosed(session)).isFalse();
        verify(logical, never()).getPhysicalConnection();
    }

    @Test
    void shouldKeepARollbackFailureWhenTheConnectionCannotBeRead() throws SQLException {
        when(logical.isPhysicallyConnected()).thenReturn(true);
        when(logical.getPhysicalConnection()).thenReturn(physical);
        when(physical.isClosed()).thenThrow(new SQLException("unreadable"));

        assertThat(EndedSessionTransactionManager.connectionClosed(session)).isFalse();
        assertThat(EndedSessionTransactionManager.connectionClosed(null)).isFalse();
    }
}
