package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tracks which repositories each workspace monitors. Queries filter by workspace id or
 * {@code nameWithOwner}, which resolves workspace context during sync.
 */
@Repository
@WorkspaceAgnostic("Configuration table mapping repositories to workspaces")
public interface RepositoryToMonitorRepository extends JpaRepository<RepositoryToMonitor, Long> {
    boolean existsByWorkspaceIdAndNameWithOwner(Long workspaceId, String nameWithOwner);

    Optional<RepositoryToMonitor> findByWorkspaceIdAndNameWithOwner(Long workspaceId, String nameWithOwner);

    List<RepositoryToMonitor> findByWorkspaceId(Long workspaceId);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.recentSyncError = :error WHERE m.id = :id")
    int updateRecentSyncError(@Param("id") Long id, @Param("error") @Nullable String error);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.historicalBackfillSyncError = :error WHERE m.id = :id")
    int updateHistoricalBackfillSyncError(@Param("id") Long id, @Param("error") @Nullable String error);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.unavailableRetryAt = :reservedUntil "
            + "WHERE m.workspace.id = :workspaceId AND m.id = :id AND m.unavailableSince IS NOT NULL "
            + "AND (m.unavailableRetryAt IS NULL OR m.unavailableRetryAt <= :now)")
    int reserveUnavailableRecheck(Long workspaceId, Long id, Instant now, Instant reservedUntil);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.unavailableRetryAt = CASE WHEN m.unavailableSince IS NULL "
            + "THEN cast(:now as Instant) ELSE cast(:retryAt as Instant) END, m.unavailableSince = COALESCE(m.unavailableSince, :now) "
            + "WHERE m.workspace.id = :workspaceId AND m.id = :id")
    int recordUnavailable(Long workspaceId, Long id, Instant now, Instant retryAt);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.unavailableSince = NULL, m.unavailableRetryAt = NULL "
            + "WHERE m.workspace.id = :workspaceId AND m.id = :id")
    int clearUnavailable(Long workspaceId, Long id);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.unavailableRetryAt = NULL "
            + "WHERE m.workspace.id = :workspaceId AND m.unavailableSince IS NOT NULL")
    int recheckUnavailable(Long workspaceId);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.unavailableRetryAt = NULL "
            + "WHERE m.workspace.id = :workspaceId AND m.id = :id")
    int retryUnavailable(Long workspaceId, Long id);

    boolean existsByWorkspaceIdAndIdAndUnavailableSinceIsNotNull(Long workspaceId, Long id);

    /** Resolves which workspace a repository belongs to during sync, by full name (owner/name). */
    Optional<RepositoryToMonitor> findByNameWithOwner(String nameWithOwner);

    /** Every workspace monitoring this repository, with workspace data ready for callers. */
    @Query(
            "SELECT m FROM RepositoryToMonitor m JOIN FETCH m.workspace WHERE m.nameWithOwner = :nameWithOwner ORDER BY m.workspace.id")
    List<RepositoryToMonitor> findAllWithWorkspaceByNameWithOwner(@Param("nameWithOwner") String nameWithOwner);

    /**
     * Finds every monitor tracking the repository with the given provider-stable id — across all
     * workspaces, because a repository can be monitored by several tenants at once. Used to re-key
     * {@code nameWithOwner} after an upstream rename/transfer, where the name is exactly the value
     * that has gone stale and so cannot be the lookup key.
     */
    List<RepositoryToMonitor> findByNativeId(Long nativeId);

    /** One workspace's monitors of the repository with the given provider-stable id. */
    List<RepositoryToMonitor> findByWorkspaceIdAndNativeId(Long workspaceId, Long nativeId);

    /** How many workspaces monitor a repository — the orphan check before deleting a shared repository row. */
    long countByNameWithOwner(String nameWithOwner);

    /** Deletes all repository monitors for a workspace (workspace purge). */
    @Modifying
    @Transactional
    @Query("DELETE FROM RepositoryToMonitor rtm WHERE rtm.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE repository_to_monitor SET
                backfill_repair_provider_count=:providerCount,
                issue_backfill_high_water_mark=NULL, issue_backfill_checkpoint=NULL, issue_sync_cursor=NULL,
                pull_request_backfill_high_water_mark=NULL, pull_request_backfill_checkpoint=NULL,
                pull_request_sync_cursor=NULL, historical_backfill_sync_error=NULL
            WHERE workspace_id=:workspaceId AND id=:syncTargetId
              AND pull_request_backfill_high_water_mark IS NOT NULL
              AND (pull_request_backfill_high_water_mark=0 OR pull_request_backfill_checkpoint<=0)
              AND (backfill_repair_provider_count IS NULL OR :storedCount>=backfill_repair_provider_count)
            """, nativeQuery = true)
    int restartCompletedBackfill(
            @Param("workspaceId") long workspaceId,
            @Param("syncTargetId") long syncTargetId,
            @Param("providerCount") int providerCount,
            @Param("storedCount") long storedCount);
}
