package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.framework.MonitoredRepositoryScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RepositoryToMonitorRepository extends JpaRepository<RepositoryToMonitor, Long> {
    @Modifying
    @Query(
            value = "UPDATE repository_to_monitor rtm SET git_refs_read_id=:readId,git_refs_fingerprint=NULL "
                    + "FROM repository r WHERE r.id=:repositoryId AND r.name_with_owner=rtm.name_with_owner "
                    + "AND rtm.workspace_id=:workspaceId AND " + MonitoredRepositoryScope.NATIVE_RTM_R,
            nativeQuery = true)
    int beginGitRefRead(long workspaceId, long repositoryId, UUID readId);

    @Modifying
    @Query(
            value = "UPDATE repository_to_monitor rtm SET git_refs_fingerprint=:fingerprint "
                    + "FROM repository r WHERE r.id=:repositoryId AND r.name_with_owner=rtm.name_with_owner "
                    + "AND rtm.workspace_id=:workspaceId AND rtm.git_refs_read_id=:readId AND "
                    + MonitoredRepositoryScope.NATIVE_RTM_R,
            nativeQuery = true)
    int recordGitRefs(long workspaceId, long repositoryId, UUID readId, String fingerprint);

    @Query(
            value = "SELECT rtm.git_refs_fingerprint FROM repository_to_monitor rtm JOIN repository r "
                    + "ON r.name_with_owner=rtm.name_with_owner WHERE r.id=:repositoryId "
                    + "AND rtm.workspace_id=:workspaceId AND " + MonitoredRepositoryScope.NATIVE_RTM_R,
            nativeQuery = true)
    Optional<String> gitRefs(long workspaceId, long repositoryId);

    @Modifying
    @WorkspaceAgnostic("An admitted provider ref event invalidates only monitors on that exact source provider")
    @Query(
            value = "UPDATE repository_to_monitor rtm SET git_refs_read_id=NULL,git_refs_fingerprint=NULL "
                    + "WHERE rtm.native_id=:nativeId AND " + MonitoredRepositoryScope.NATIVE_RTM_SOURCE_PROVIDER,
            nativeQuery = true)
    int invalidateGitRefs(long nativeId, long providerId);

    boolean existsByWorkspaceIdAndNameWithOwner(Long workspaceId, String nameWithOwner);

    Optional<RepositoryToMonitor> findByWorkspaceIdAndNameWithOwner(Long workspaceId, String nameWithOwner);

    List<RepositoryToMonitor> findByWorkspaceId(Long workspaceId);

    @Modifying
    @Transactional
    @WorkspaceAgnostic("Keyed by the sync target id of the sync run that records its own error")
    @Query("UPDATE RepositoryToMonitor m SET m.recentSyncError = :error WHERE m.id = :id")
    int updateRecentSyncError(@Param("id") Long id, @Param("error") @Nullable String error);

    @Modifying
    @Transactional
    @WorkspaceAgnostic("Keyed by the sync target id of the sync run that records its own error")
    @Query("UPDATE RepositoryToMonitor m SET m.historicalBackfillSyncError = :error WHERE m.id =" + " :id")
    int updateHistoricalBackfillSyncError(@Param("id") Long id, @Param("error") @Nullable String error);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.unavailableRetryAt = :reservedUntil WHERE"
            + " m.workspace.id = :workspaceId AND m.id = :id AND m.unavailableSince IS NOT NULL"
            + " AND (m.unavailableRetryAt IS NULL OR m.unavailableRetryAt <= :now)")
    int reserveUnavailableRecheck(Long workspaceId, Long id, Instant now, Instant reservedUntil);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.unavailableRetryAt = CASE WHEN m.unavailableSince"
            + " IS NULL THEN cast(:now as Instant) ELSE cast(:retryAt as Instant) END,"
            + " m.unavailableSince = COALESCE(m.unavailableSince, :now) WHERE m.workspace.id ="
            + " :workspaceId AND m.id = :id")
    int recordUnavailable(Long workspaceId, Long id, Instant now, Instant retryAt);

    @Modifying
    @Transactional
    @Query("UPDATE RepositoryToMonitor m SET m.unavailableSince = NULL, m.unavailableRetryAt ="
            + " NULL WHERE m.workspace.id = :workspaceId AND m.id = :id")
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
    @WorkspaceAgnostic("Resolves the workspace of a repository by its full name, before any workspace is known")
    Optional<RepositoryToMonitor> findByNameWithOwner(String nameWithOwner);

    @WorkspaceAgnostic("The receiver fans out a verified event to each workspace that monitors its repository")
    @Query("SELECT m.workspace.id FROM RepositoryToMonitor m WHERE m.nameWithOwner = :nameWithOwner"
            + " ORDER BY m.workspace.id")
    List<Long> findWorkspaceIdsByNameWithOwner(@Param("nameWithOwner") String nameWithOwner);

    /** Resolve monitors by provider-stable id across workspaces; a renamed path cannot serve as the lookup key. */
    @WorkspaceAgnostic(
            "A provider event fans out to every workspace that monitors the repository, by its provider-stable id")
    @Query(
            value = "SELECT rtm.* FROM repository_to_monitor rtm WHERE rtm.native_id = :nativeId"
                    + " AND "
                    + MonitoredRepositoryScope.NATIVE_RTM_SOURCE_PROVIDER,
            nativeQuery = true)
    List<RepositoryToMonitor> findByNativeIdOnSourceProvider(
            @Param("nativeId") Long nativeId, @Param("providerId") Long providerId);

    /**
     * A stored id is foreign only with a verified own-provider replacement and no own-provider
     * owner of the old id.
     */
    @WorkspaceAgnostic("Keyed by a monitor id that the caller already holds from its own workspace")
    @Query(
            value = """
                    SELECT EXISTS (SELECT 1 FROM repository_to_monitor rtm JOIN repository r ON r.native_id = :resolvedNativeId
                                   WHERE rtm.id = :monitorId AND
                    """
                    + MonitoredRepositoryScope.NATIVE_RTM_SOURCE_OF_R
                    + """
                    ) AND EXISTS (SELECT 1 FROM repository_to_monitor rtm JOIN repository r
                                   ON r.name_with_owner = rtm.name_with_owner AND r.native_id = rtm.native_id
                                   WHERE rtm.id = :monitorId AND NOT
                    """
                    + MonitoredRepositoryScope.NATIVE_RTM_SOURCE_OF_R
                    + """
                    ) AND NOT EXISTS (SELECT 1 FROM repository_to_monitor rtm JOIN repository r ON r.native_id = rtm.native_id
                                   WHERE rtm.id = :monitorId AND
                    """
                    + MonitoredRepositoryScope.NATIVE_RTM_SOURCE_OF_R
                    + """
                    )
                    """,
            nativeQuery = true)
    boolean monitorHoldsAnotherInstancesId(
            @Param("monitorId") Long monitorId, @Param("resolvedNativeId") Long resolvedNativeId);

    /** Exact mirrored repository ids covered by one workspace, before its monitors are removed. */
    @Query(
            value = "SELECT DISTINCT r.id FROM repository_to_monitor rtm JOIN repository r ON"
                    + " r.name_with_owner = rtm.name_with_owner WHERE rtm.workspace_id ="
                    + " :workspaceId AND "
                    + MonitoredRepositoryScope.NATIVE_RTM_R,
            nativeQuery = true)
    List<Long> findSourceRepositoryIds(@Param("workspaceId") Long workspaceId);

    /** This workspace's monitors of the exact source repository. */
    @Query(
            value = "SELECT rtm.* FROM repository_to_monitor rtm JOIN repository r ON"
                    + " r.name_with_owner = rtm.name_with_owner WHERE r.id = :repositoryId"
                    + " AND rtm.workspace_id = :workspaceId AND "
                    + MonitoredRepositoryScope.NATIVE_RTM_R,
            nativeQuery = true)
    List<RepositoryToMonitor> findByWorkspaceIdAndSourceRepositoryId(
            @Param("workspaceId") Long workspaceId, @Param("repositoryId") Long repositoryId);

    /** Exact mirrored repository ids covered by the monitor being removed. */
    @WorkspaceAgnostic("Keyed by a monitor id that the caller already holds from its own workspace")
    @Query(
            value = "SELECT r.id FROM repository_to_monitor rtm JOIN repository r ON"
                    + " r.name_with_owner = rtm.name_with_owner WHERE rtm.id = :monitorId AND "
                    + MonitoredRepositoryScope.NATIVE_RTM_R,
            nativeQuery = true)
    List<Long> findSourceRepositoryIdsOfMonitor(@Param("monitorId") Long monitorId);

    /**
     * Remaining monitors of this exact source repository; a foreign same-named repository does not
     * count.
     */
    @WorkspaceAgnostic(
            "deleteRepositoryIfOrphaned deletes a shared mirror repository only when no workspace monitors it, so it counts the monitors of every workspace")
    @Query(
            value = "SELECT COUNT(*) FROM repository_to_monitor rtm JOIN repository r ON"
                    + " r.name_with_owner = rtm.name_with_owner WHERE r.id = :repositoryId AND "
                    + MonitoredRepositoryScope.NATIVE_RTM_R,
            nativeQuery = true)
    long countForRepository(@Param("repositoryId") Long repositoryId);

    /** One workspace's monitors of the repository with the given provider-stable id. */
    List<RepositoryToMonitor> findByWorkspaceIdAndNativeId(Long workspaceId, Long nativeId);

    /** The workspace's monitor of the repository with the given provider-stable id; a workspace holds at most one. */
    Optional<RepositoryToMonitor> findOneByWorkspaceIdAndNativeId(Long workspaceId, Long nativeId);

    boolean existsByWorkspaceIdAndNativeIdAndIdNot(Long workspaceId, Long nativeId, Long id);

    boolean existsByWorkspaceIdAndNameWithOwnerAndIdNot(Long workspaceId, String nameWithOwner, Long id);

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
          AND (:repairIncompleteCoverage OR backfill_repair_provider_count IS NULL OR :storedCount>=backfill_repair_provider_count)
        """, nativeQuery = true)
    int restartCompletedBackfill(
            @Param("workspaceId") long workspaceId,
            @Param("syncTargetId") long syncTargetId,
            @Param("providerCount") int providerCount,
            @Param("storedCount") long storedCount,
            @Param("repairIncompleteCoverage") boolean repairIncompleteCoverage);
}
