package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.UUID;

/** Source currentness at practice start and at a review's claim or seal. */
public interface ReviewFreshness {
    Currentness capturedCurrentness(long workspaceId, UUID reviewId);

    /**
     * Whether the sources that the practice's capture read still hold, as {@link #start} records it. The check reads
     * the item's record from this server's mirror, which can take a while, so it runs before the locked start and holds
     * no review, job or run lock (docs/contributor/agent-runs.mdx § Locks and slow work). A source that changes after it
     * is caught when the review's results are admitted, as any change after a start is.
     */
    Currentness startCurrentness(long workspaceId, ReviewAssignmentId assignment);

    /** Starts the practice if {@code checked} is current, and withholds it otherwise; the claim decides first. */
    boolean start(long workspaceId, ReviewAssignmentId assignment, UUID jobId, int attempt, Currentness checked);

    /**
     * Brings this server's copy of what {@link #startCurrentness} reads up to date with the provider. It may call the
     * provider, so it runs with no transaction, before the check.
     */
    void syncSources(long workspaceId, ReviewAssignmentId assignment);

    /**
     * Brings this server's copy of what a freshness read of these results reads up to date with the provider, before
     * the transaction that decides their feedback. It may call the provider, so it runs with no transaction.
     */
    void syncResults(long workspaceId, Collection<UUID> resultIds);
}
