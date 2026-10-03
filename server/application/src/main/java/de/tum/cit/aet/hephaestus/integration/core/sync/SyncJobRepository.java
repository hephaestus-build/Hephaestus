package de.tum.cit.aet.hephaestus.integration.core.sync;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {
    Optional<SyncJob> findByIdAndWorkspace_Id(Long id, long workspaceId);

    /** Job history page. Callers supply the sort (see {@code SyncStatusService}, {@code createdAt} desc). */
    Page<SyncJob> findByConnection_IdAndWorkspace_Id(long connectionId, long workspaceId, Pageable pageable);

    Page<SyncJob> findByWorkspace_Id(long workspaceId, Pageable pageable);

    boolean existsByWorkspace_IdAndStatusIn(long workspaceId, Collection<SyncJobStatus> statuses);

    /** The one-active-job guard's read: present result means the connection already has a job in flight. */
    Optional<SyncJob> findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
            long connectionId, Collection<SyncJobStatus> statuses);

    /** Most recent job that has actually finished — the source for connection health derivation. */
    Optional<SyncJob> findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(
            long connectionId, Collection<SyncJobStatus> statuses);

    /**
     * Zombie sweep across every workspace: {@code PENDING}/{@code RUNNING} jobs whose lease
     * (heartbeat, falling back to creation time before the first heartbeat lands) is stale.
     */
    @WorkspaceAgnostic("Cross-workspace zombie sweep; caller is the @ConditionalOnServerRole sweeper")
    @Query(
            value = "SELECT * FROM sync_job WHERE status IN ('PENDING', 'RUNNING') "
                    + "AND COALESCE(heartbeat_at, created_at) < now() - make_interval(secs => :leaseTtlSeconds)",
            nativeQuery = true)
    List<SyncJob> findAbandoned(@Param("leaseTtlSeconds") long leaseTtlSeconds);

    /**
     * Same as {@link #findAbandoned} but scoped to one connection — run inline before the
     * one-active-job guard rejects a manual trigger, so a crashed prior run never blocks
     * "Sync now" for the full sweep interval.
     */
    @WorkspaceAgnostic("ID-based inline reap; connectionId comes from a workspace-scoped caller")
    @Query(
            value = "SELECT * FROM sync_job WHERE connection_id = :connectionId "
                    + "AND status IN ('PENDING', 'RUNNING') "
                    + "AND COALESCE(heartbeat_at, created_at) < now() - make_interval(secs => :leaseTtlSeconds)",
            nativeQuery = true)
    List<SyncJob> findAbandonedForConnection(
            @Param("connectionId") long connectionId, @Param("leaseTtlSeconds") long leaseTtlSeconds);

    /** Flag-only cancel write, guarded so it never touches a job that already reached a terminal status. */
    @WorkspaceAgnostic("ID-based cancel flag; jobId is validated against the workspace by the caller first")
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SyncJob j SET j.cancelRequested = true WHERE j.id = :id AND j.status IN :statuses")
    int markCancelRequested(@Param("id") long id, @Param("statuses") Collection<SyncJobStatus> statuses);

    /** Status-guarded bulk reap for the zombie sweep — only rows still in a non-terminal status flip. */
    @WorkspaceAgnostic("ID-based reap; ids come from the cross-workspace zombie sweep")
    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE sync_job SET status = 'FAILED', finished_at = now(), error_summary = :reason "
                    + "WHERE id = :id AND status IN ('PENDING', 'RUNNING') "
                    + "AND COALESCE(heartbeat_at, created_at) < now() - make_interval(secs => :leaseTtlSeconds)",
            nativeQuery = true)
    int markAbandoned(
            @Param("id") long id, @Param("reason") String reason, @Param("leaseTtlSeconds") long leaseTtlSeconds);

    @WorkspaceAgnostic("ID-based PENDING claim; jobId comes from the job created by the caller")
    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE sync_job SET status = 'RUNNING', started_at = now(), heartbeat_at = now() "
                    + "WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int markRunning(@Param("id") long id);

    /**
     * Terminal transition, compare-and-set on {@code status IN :activeStatuses} so a late writer cannot
     * overwrite a row that already finished.
     *
     * <p>Deliberately does not filter on {@code cancelRequested}: the outcome belongs to the runner that
     * executed the body (see {@code SyncJobService#executeBody}), so a cancel request the runner never
     * honored must not turn its reported outcome into CANCELLED.
     */
    @WorkspaceAgnostic("ID-based terminal transition; jobId comes from the active in-process handle")
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SyncJob j SET j.status = :status, j.finishedAt = instant, j.errorSummary = :errorSummary, "
            + "j.itemsProcessed = :itemsProcessed, j.itemsTotal = :itemsTotal, j.progress = :progress "
            + "WHERE j.id = :id AND j.status IN :activeStatuses")
    int completeActiveJob(
            @Param("id") long id,
            @Param("status") SyncJobStatus status,
            @Param("errorSummary") @Nullable String errorSummary,
            @Param("itemsProcessed") @Nullable Integer itemsProcessed,
            @Param("itemsTotal") @Nullable Integer itemsTotal,
            @Param("progress") Map<String, Object> progress,
            @Param("activeStatuses") Collection<SyncJobStatus> activeStatuses);

    /** Bulk lease touch for every currently-registered in-JVM handle (the 60s heartbeat scheduler). */
    @WorkspaceAgnostic("ID-based bulk heartbeat; ids come from the in-process job handle registry")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE sync_job SET heartbeat_at = now() WHERE id IN (:ids) AND status = 'RUNNING'",
            nativeQuery = true)
    int touchHeartbeat(@Param("ids") Collection<Long> ids);

    /** Cancel-flag projection, read back on the same heartbeat pass so a cancel request reaches the runner. */
    interface CancelFlagProjection {
        Long getId();

        boolean isCancelRequested();
    }

    @WorkspaceAgnostic("ID-based cancel-flag refresh; ids come from the in-process job handle registry")
    @Query("SELECT j.id AS id, j.cancelRequested AS cancelRequested FROM SyncJob j WHERE j.id IN :ids")
    List<CancelFlagProjection> findCancelFlags(@Param("ids") Collection<Long> ids);

    /**
     * Retention: keep only the newest {@code limit} rows per connection, pruning the rest right
     * after insert. This is a live-operations view, not an audit trail — no separate sweeper needed.
     */
    @WorkspaceAgnostic(
            "ID-based retention prune; connectionId comes from the job just created in a workspace-scoped call")
    @Modifying
    @Query(
            value =
                    "DELETE FROM sync_job WHERE connection_id = :connectionId AND id NOT IN "
                            + "(SELECT id FROM sync_job WHERE connection_id = :connectionId ORDER BY created_at DESC LIMIT :limit)",
            nativeQuery = true)
    int pruneOldJobs(@Param("connectionId") long connectionId, @Param("limit") int limit);
}
