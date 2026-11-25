package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface HistoryCustodyQuery {
    HistoryCustody find(long workspaceId, List<UUID> producingJobIds);

    /** {@link #find} of each producing job on its own, read in one batch for a read that checks many of them. */
    Map<UUID, HistoryCustody> findEach(long workspaceId, Collection<UUID> producingJobIds);

    /** The job that produced a result of {@code reviewId}. */
    record Receipt(UUID reviewId, UUID producingJobId) {}

    /**
     * When each receipt's producing job captured its evidence, as its retained capture manifest records it. A receipt
     * is absent when its job, its review or a valid manifest time is missing.
     */
    Map<Receipt, Instant> capturedAt(long workspaceId, Collection<Receipt> receipts);
}
