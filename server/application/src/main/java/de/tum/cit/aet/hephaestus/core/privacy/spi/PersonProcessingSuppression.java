package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Exact-key processing fence; must be checked again after a mirror is recreated. */
public interface PersonProcessingSuppression {
    boolean isSuppressed(long providerId, String subject, @Nullable String teamId);

    boolean isAccountSuppressed(long accountId);

    Set<Long> suppressedAccountIds(Collection<Long> accountIds);

    /** Frozen review jobs are unavailable from erasure admission through every resumable step. */
    boolean isReviewJobSuppressed(UUID jobId);

    /** {@link #isReviewJobSuppressed} of many jobs in one read: the jobs among them that are suppressed. */
    Set<UUID> suppressedReviewJobs(Collection<UUID> jobIds);

    /**
     * Current source attribution, including resynced provider records, scoped to the requesting
     * workspace.
     */
    boolean isSourceSuppressed(long workspaceId, String itemKind, long sourceId);

    /** {@link #isSourceSuppressed} of many sources of one kind in one read: the sources among them that are suppressed. */
    Set<Long> suppressedSources(long workspaceId, String itemKind, Collection<Long> sourceIds);
}
