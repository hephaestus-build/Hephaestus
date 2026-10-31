package de.tum.cit.aet.hephaestus.agent.job;

import java.util.UUID;

/**
 * Projection of an orphaned RUNNING job, so the recovery sweep can requeue it without lazy-loading the
 * {@code Workspace} entity outside a transaction.
 */
public interface OrphanedJobRef {
    UUID getJobId();

    Long getWorkspaceId();

    int getAttempt();

    /**
     * The lease of the expired attempt. Threaded back into the requeue CAS so the sweeper reclaims the row only while
     * it still carries the lease it judged expired, never a later claim of the same job.
     */
    UUID getLeaseId();
}
