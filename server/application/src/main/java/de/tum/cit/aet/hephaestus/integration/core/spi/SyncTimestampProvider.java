package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncMetadata;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import java.time.Instant;
import java.util.Optional;

/**
 * Provides sync timestamp operations for the integration.scm ETL engine.
 * <p>
 * This interface handles scope-level sync timestamps, including:
 * <ul>
 *   <li>Issue types, issue dependencies, and sub-issues sync timestamps</li>
 *   <li>User and team sync timestamps</li>
 * </ul>
 * <p>
 * Extracted from {@link SyncTargetProvider} to comply with the Interface Segregation Principle.
 * Services that only need timestamp operations should depend on this interface.
 *
 * @see SyncTargetProvider for repository-level sync operations
 * @see BackfillStateProvider for backfill tracking operations
 */
public interface SyncTimestampProvider {
    /**
     * Gets the sync metadata for a scope (issue types, dependencies, sub-issues).
     *
     * @param scopeId the scope ID
     * @return the sync metadata, or empty if scope not found
     */
    Optional<SyncMetadata> getSyncMetadata(Long scopeId);

    /**
     * Updates the sync timestamp for scope-level sync operations.
     *
     * @param scopeId  the scope ID
     * @param syncType the type of sync (ISSUE_TYPES, ISSUE_DEPENDENCIES, SUB_ISSUES)
     * @param syncedAt the timestamp of the sync
     */
    void updateScopeSyncTimestamp(Long scopeId, SyncType syncType, Instant syncedAt);

    /**
     * Updates the users sync timestamp for a scope.
     *
     * @param scopeId  the scope ID
     * @param syncedAt the timestamp of the sync
     */
    void updateUsersSyncTimestamp(Long scopeId, Instant syncedAt);

    /**
     * Updates the teams sync timestamp for a scope.
     *
     * @param scopeId  the scope ID
     * @param syncedAt the timestamp of the sync
     */
    void updateTeamsSyncTimestamp(Long scopeId, Instant syncedAt);
}
