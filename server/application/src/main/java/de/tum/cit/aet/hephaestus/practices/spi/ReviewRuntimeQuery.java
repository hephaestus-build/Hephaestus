package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ReviewRuntimeQuery {
    Optional<ReviewRuntimeSnapshot> runtime(long workspaceId, UUID reviewId);

    /** Each review's jobs counted by status, for many reviews in one read; a review without jobs is absent. */
    Map<UUID, Map<ReviewJobStatus, Long>> jobCounts(long workspaceId, Collection<UUID> reviewIds);

    Set<UUID> activeJobIds(long workspaceId, UUID reviewId);

    Set<UUID> unsettledJobIds(long workspaceId, UUID reviewId);

    ReviewUsageSnapshot usage(long workspaceId, UUID reviewId);

    /**
     * Retained canonical usage for the requested reviews. An absent entry means no ledger rows
     * remain for that review, not measured zero usage. Totals belong to the whole review.
     */
    Map<UUID, ReviewUsageSnapshot> usageByReview(long workspaceId, Set<UUID> reviewIds);

    ReviewQueueSnapshot queue(long workspaceId, Instant asOf, int headLimit);

    ReviewTimingSnapshot timing(long workspaceId, Instant from, Instant to, Instant longWaitThreshold);
}
