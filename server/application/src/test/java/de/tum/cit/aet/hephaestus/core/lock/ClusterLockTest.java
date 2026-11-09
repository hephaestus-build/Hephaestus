package de.tum.cit.aet.hephaestus.core.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** A connection that may still hold the lock never returns to the pool, and its release never fails the work. */
class ClusterLockTest extends BaseUnitTest {

    @Test
    void shouldAbortTheConnectionAndKeepTheResultWhenTheUnlockFails() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement lock = statement(true);
        when(connection.prepareStatement(startsWith("SELECT pg_try_advisory_lock")))
                .thenReturn(lock);
        when(connection.prepareStatement(startsWith("SELECT pg_advisory_unlock")))
                .thenThrow(new SQLException("connection lost"));
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertThat(new ClusterLock(dataSource).call("work", Duration.ZERO, () -> "done"))
                .contains("done");

        verify(connection).abort(any(Executor.class));
        verify(connection, never()).close();
    }

    @Test
    void shouldReturnTheConnectionAtOnceWhenAnotherProcessHoldsTheLock() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement lock = statement(false);
        when(connection.prepareStatement(startsWith("SELECT pg_try_advisory_lock")))
                .thenReturn(lock);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertThat(new ClusterLock(dataSource).call("work", Duration.ZERO, () -> "done"))
                .isEmpty();

        verify(connection).close();
    }

    @Test
    void shouldAbortTheConnectionWhenTheLockQueryFailsAfterTheServerMayHaveTakenTheLock() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(startsWith("SELECT pg_try_advisory_lock")))
                .thenThrow(new SQLException("socket timeout"));
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertThatThrownBy(() -> new ClusterLock(dataSource).call("work", Duration.ZERO, () -> "done"))
                .isInstanceOf(ClusterLock.UnavailableException.class);

        verify(connection).abort(any(Executor.class));
        verify(connection, never()).close();
    }

    private static PreparedStatement statement(boolean answer) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(answer);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.executeQuery()).thenReturn(result);
        return statement;
    }
}
