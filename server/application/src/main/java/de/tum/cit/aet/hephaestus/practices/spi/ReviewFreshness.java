package de.tum.cit.aet.hephaestus.practices.spi;

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
}
