package de.tum.cit.aet.hephaestus.core;

/**
 * The maintenance barrier: no claim takes a new lease while a database migration runs, so a migration never converts
 * a row that a claim of the previous release starts to write (ADR 0096). A migration holds the
 * session advisory lock {@link #MIGRATION_LOCK} from before Liquibase reads its history until the migration ends,
 * also when it fails ({@code core.database.MigrationLock}). PostgreSQL drops a session lock with its session, so a
 * killed migration cannot leave the barrier up.
 *
 * <p>A claim takes the same lock shared for its transaction, without waiting ({@link #LIFTED}). While a migration
 * holds the lock, the claim leases nothing. A migration takes the lock only after the claims in flight commit. Only
 * new leases stop: a holder still renews, requeues and ends its lease, so the work in flight finishes.
 */
public final class MaintenanceBarrier {

    /** The key of the advisory lock that a database migration holds while it runs. */
    public static final String MIGRATION_LOCK = "hashtext('hephaestus'), hashtext('database-migration')";

    /**
     * The condition that every claim adds to its {@code WHERE} clause: true only while no migration runs. It is an
     * uncorrelated subquery, so PostgreSQL tests it once for each statement, before it reads a row.
     */
    public static final String LIFTED = "(SELECT pg_try_advisory_xact_lock_shared(" + MIGRATION_LOCK + "))";

    private MaintenanceBarrier() {}
}
