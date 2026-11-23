package de.tum.cit.aet.hephaestus.core;

/**
 * The maintenance barrier: no server starts new work while a database migration runs, so a migration never converts a
 * row that a start writes (ADR 0096). A migration holds the session advisory lock {@link #MIGRATION_LOCK} on the
 * session that runs its change sets, from before Liquibase reads its history until the migration ends
 * ({@code core.database.MigrationLock}). The barrier thus ends with the migration's session, also when that session is
 * killed.
 *
 * <p>A start takes the same lock shared for its transaction, without waiting, before it writes: a claim in its
 * {@code WHERE} clause, and a start that records itself first with {@link #LIFTED} as the first statement of its
 * transaction. The lock lasts until that transaction ends. While a migration holds the lock, or waits for it, nothing
 * starts. A database trigger refuses every new lease that a statement takes without the lock. A holder still renews,
 * requeues and ends its lease, so the work in flight finishes.
 */
public final class MaintenanceBarrier {

    /** The key of the advisory lock that a database migration holds while it runs. */
    public static final String MIGRATION_LOCK = "hashtext('hephaestus'), hashtext('database-migration')";

    /**
     * True only while no migration runs or waits, and then the barrier's lock is held shared until the transaction
     * ends. It is an uncorrelated subquery, so PostgreSQL tests it once for each statement, before it reads a row, and
     * it reads no table, so a migration's table lock cannot keep it waiting.
     */
    public static final String LIFTED = "(SELECT pg_try_advisory_xact_lock_shared(" + MIGRATION_LOCK + "))";

    private MaintenanceBarrier() {}
}
