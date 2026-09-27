package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Provides backfill state management for repository synchronization.
 * <p>
 * Backfill is the process of incrementally syncing historical data for a repository.
 * This interface tracks:
 * <ul>
 *   <li>High water mark: The highest issue/PR number when backfill started</li>
 *   <li>Checkpoint: The current position in the backfill process</li>
 *   <li>Last run time: When the backfill was last executed</li>
 * </ul>
 * <p>
 * Services that only manage backfill state depend on this narrow interface rather than the full
 * {@link SyncTargetProvider}.
 *
 * @see SyncTargetProvider for full sync target operations
 */
public interface BackfillStateProvider {
    Optional<SyncTarget> findSyncTargetById(Long syncTargetId);

    /**
     * Updates the issue backfill state for a sync target.
     *
     * @param syncTargetId   the sync target ID
     * @param highWaterMark  the high water mark (highest issue number at backfill start), or null to keep current
     * @param checkpoint     the current checkpoint (lowest issue number synced), or null to keep current
     * @param lastRunAt      when the backfill was last run, or null to keep current
     */
    void updateIssueBackfillState(
            Long syncTargetId,
            @Nullable Integer highWaterMark,
            @Nullable Integer checkpoint,
            @Nullable Instant lastRunAt);

    /**
     * Updates the pull request backfill state for a sync target.
     *
     * @param syncTargetId   the sync target ID
     * @param highWaterMark  the high water mark (highest pull request number at backfill start), or null to keep current
     * @param checkpoint     the current checkpoint (lowest pull request number synced), or null to keep current
     * @param lastRunAt      when the backfill was last run, or null to keep current
     */
    void updatePullRequestBackfillState(
            Long syncTargetId,
            @Nullable Integer highWaterMark,
            @Nullable Integer checkpoint,
            @Nullable Instant lastRunAt);

    /**
     * Removes a sync target. Called when a repository no longer exists upstream (NOT_FOUND)
     * to stop perpetual sync retries for a deleted repository.
     */
    void removeSyncTarget(Long syncTargetId);

    /**
     * Folds sync target {@code duplicateSyncTargetId} into {@code keptSyncTargetId}, two targets of one scope for the
     * same repository: the kept target keeps its own state and takes over any practice review selection the duplicate
     * carried, and the duplicate is removed. A no-op unless both exist in the same scope.
     */
    void mergeSyncTarget(Long keptSyncTargetId, Long duplicateSyncTargetId);

    /**
     * Reconciles a sync target's stable identity against what the provider currently reports for the
     * repository, healing a rename/transfer:
     * <ul>
     *   <li>If the target has no {@code nativeId} yet (legacy/PAT row), captures {@code resolvedNativeId}.</li>
     *   <li>If {@code resolvedNameWithOwner} differs from the stored name — and the stable id agrees —
     *       re-keys the monitor's {@code nameWithOwner} and refreshes the workspace's NATS consumer so
     *       the subject filter tracks the new name. Never renames by name alone.</li>
     * </ul>
     * Idempotent and safe to call every sync cycle; a no-op when nothing changed. Implementations must
     * not throw for a missing sync target (it may have been removed mid-sync).
     *
     * @param syncTargetId          the sync target (monitor) id
     * @param resolvedNativeId      the provider's stable numeric repository id, or {@code null} if unknown
     * @param resolvedNameWithOwner the repository's current {@code owner/name} as the provider reports it
     */
    default void reconcileSyncTargetIdentity(
            Long syncTargetId,
            @org.jspecify.annotations.Nullable Long resolvedNativeId,
            @org.jspecify.annotations.Nullable String resolvedNameWithOwner) {
        // Default no-op: providers/tests that don't track a stable monitor identity keep prior behavior.
    }

    /**
     * Re-keys <em>every</em> sync target that tracks the given repository, identified by the provider's
     * stable numeric id rather than by name. This is the webhook-driven sibling of
     * {@link #reconcileSyncTargetIdentity}: a rename/transfer arrives as a single vendor event that
     * names no sync target, and the repository may be monitored by several scopes at once (mirrored
     * repositories are instance-global and shared across workspaces), so the caller has neither a
     * {@code syncTargetId} nor a way to enumerate them. Implementations resolve the affected targets by
     * {@code nativeId} and apply the same re-key + consumer-refresh semantics per target.
     * <p>
     * A no-op when {@code nativeId} is unknown (never re-key by name alone) or when no target tracks it.
     *
     * @param nativeId          the provider's stable numeric repository id from the webhook payload
     * @param newNameWithOwner  the repository's new {@code owner/name} as the payload reports it
     */
    default void reconcileSyncTargetsForRepository(
            @org.jspecify.annotations.Nullable Long nativeId,
            @org.jspecify.annotations.Nullable String newNameWithOwner) {
        // Default no-op: see reconcileSyncTargetIdentity.
    }

    /**
     * Updates the pagination cursor for the given {@link SyncCursorKind}, keyed by
     * {@code syncTargetId}; the implementer switches on the kind to reach the correct column.
     * <p>
     * Lets sync resume mid-pagination after interruption; persist within the same
     * transaction as the synced data for consistency.
     *
     * @param scopeId the {@code syncTargetId} (repository) the cursor belongs to
     * @param kind    the entity kind whose cursor is being persisted
     * @param cursor  the GraphQL pagination cursor, or null to clear (sync complete)
     */
    void updateSyncCursor(Long scopeId, SyncCursorKind kind, @Nullable String cursor);
}
