package de.tum.cit.aet.hephaestus.practices.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Frozen product facts needed to prepare a review on the server or worker. */
public interface ReviewSnapshotQuery {
    Optional<ReviewSnapshot> find(long workspaceId, UUID reviewId);

    /**
     * The pinned practices of each review {@link #find} finds, keyed by review id, read in a fixed number of queries.
     * A review that {@link #find} would not find is absent.
     */
    Map<UUID, List<PinnedPractice>> findPinned(long workspaceId, Collection<UUID> reviewIds);

    Optional<Instant> publicReviewedAt(long workspaceId, UUID reviewId);

    Optional<BigDecimal> budgetUsd(long workspaceId, UUID reviewId);
}
