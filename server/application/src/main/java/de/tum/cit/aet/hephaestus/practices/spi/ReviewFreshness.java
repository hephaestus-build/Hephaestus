package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.UUID;

/** Source currentness at practice start and at a review's claim or seal. */
public interface ReviewFreshness {
    Currentness capturedCurrentness(long workspaceId, UUID reviewId);

    boolean start(long workspaceId, ReviewAssignmentId assignment, UUID jobId, int attempt);

    /**
     * Brings this server's copy of what {@link #start} reads up to date with the provider. It may call the provider, so
     * it runs with no transaction, before the locked start.
     */
    void syncSources(long workspaceId, ReviewAssignmentId assignment);

    /**
     * Brings this server's copy of what a freshness read of these results reads up to date with the provider, before
     * the transaction that decides their feedback. It may call the provider, so it runs with no transaction.
     */
    void syncResults(long workspaceId, Collection<UUID> resultIds);
}
