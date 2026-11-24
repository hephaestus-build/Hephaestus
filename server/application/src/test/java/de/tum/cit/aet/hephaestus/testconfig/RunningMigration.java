package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.MaintenanceBarrier;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * A database migration that runs until it is closed: its session holds the migration lock, as {@code MigrationLock}
 * holds it, so the maintenance barrier is up ({@link MaintenanceBarrier}).
 */
public final class RunningMigration implements AutoCloseable {

    private final Connection session;

    private RunningMigration(Connection session) {
        this.session = session;
    }

    public static RunningMigration start(DataSource dataSource) throws SQLException {
        Connection session = dataSource.getConnection();
        try (var statement = session.createStatement()) {
            session.setAutoCommit(true);
            statement.execute("SELECT pg_advisory_lock(" + MaintenanceBarrier.MIGRATION_LOCK + ")");
        } catch (SQLException e) {
            session.close();
            throw e;
        }
        return new RunningMigration(session);
    }

    /**
     * The migration ends, and the barrier is lifted. A session that cannot unlock is aborted, so the lock never goes
     * back to the pool with its connection.
     */
    @Override
    public void close() throws SQLException {
        try (var statement = session.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock(" + MaintenanceBarrier.MIGRATION_LOCK + ")");
        } catch (SQLException e) {
            session.abort(Runnable::run);
            throw e;
        } finally {
            session.close();
        }
    }

    /** Runs a statement on the migration's own session, as a change set does: the barrier lets it pass. */
    public int update(String sql, Object... arguments) throws SQLException {
        try (var statement = session.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement.executeUpdate();
        }
    }
}
