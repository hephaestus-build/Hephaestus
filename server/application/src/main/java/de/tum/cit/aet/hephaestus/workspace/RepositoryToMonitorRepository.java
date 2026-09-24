package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
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

    /** How many workspaces monitor a repository — the orphan check before deleting a shared repository row. */
    long countByNameWithOwner(String nameWithOwner);

    /** Deletes all repository monitors for a workspace (workspace purge). */
    @Modifying
    @Transactional
    @Query("DELETE FROM RepositoryToMonitor rtm WHERE rtm.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
