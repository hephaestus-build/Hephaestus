package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptHistory.RetainedAttempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Repository
public interface ReviewJobRepository extends JpaRepository<ReviewJob, UUID> {
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptHistory$RetainedAttempt(
            j.id, j.reviewId, j.status, j.attempt, j.processingStatus, j.availableAt, j.startedAt, j.completedAt, j.input)
        FROM ReviewJob j WHERE j.workspaceId = :workspaceId AND j.availableAt >= :since
        ORDER BY j.availableAt DESC
        """)
    List<RetainedAttempt> findRecentAttempts(long workspaceId, Instant since, Pageable page);

    List<ReviewJob> findByWorkspaceIdAndStatusInOrderByAvailableAtAscIdAsc(
            long workspaceId, Collection<ReviewJobStatus> statuses, Limit limit);

    List<ReviewJob> findByReviewIdAndWorkspaceIdOrderByAvailableAtAsc(UUID reviewId, long workspaceId);

    Optional<ReviewJob> findByReviewIdAndWorkspaceIdAndStatusIn(
            UUID reviewId, long workspaceId, Collection<ReviewJobStatus> statuses);

    boolean existsByReviewIdAndWorkspaceIdAndCancellationReason(
            UUID reviewId, long workspaceId, ReviewJobCancellationReason cancellationReason);

    /** Whether a boundary refused one of the review's jobs because the workspace's access narrowed (ADR 0134). */
    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM review_job WHERE review_id=:reviewId AND workspace_id=:workspaceId
          AND output->>'outcome'='AUTHORITY_CHANGED')
        """, nativeQuery = true)
    boolean existsAuthorityRefusal(@Param("reviewId") UUID reviewId, @Param("workspaceId") long workspaceId);

    @Query(
            "SELECT j.id FROM ReviewJob j WHERE j.reviewId=:reviewId AND j.workspaceId=:workspaceId AND j.status IN :statuses")
    Optional<UUID> findActiveId(
            @Param("reviewId") UUID reviewId,
            @Param("workspaceId") long workspaceId,
            @Param("statuses") Collection<ReviewJobStatus> statuses);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM ReviewJob j WHERE j.workspaceId = :workspaceId")
    int deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Query("SELECT j FROM ReviewJob j  WHERE j.id = :id AND j.workspaceId = :workspaceId")
    Optional<ReviewJob> findByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /** The capture manifest that each job retains, read without the rest of its capture, input and output. */
    @Query(value = """
        SELECT j.id,j.review_id AS "reviewId",CAST(j.capture->'manifest' AS text) AS manifest
        FROM review_job j WHERE j.workspace_id=:workspaceId AND j.id IN (:ids)
        """, nativeQuery = true)
    List<CaptureManifest> findCaptureManifests(
            @Param("workspaceId") long workspaceId, @Param("ids") Collection<UUID> ids);

    interface CaptureManifest {
        UUID getId();

        UUID getReviewId();

        /** The manifest as JSON text, or {@code null} when the job retains no capture or no manifest. */
        @Nullable
        String getManifest();
    }

    /** The job that a run drives, in the run's workspace. */
    Optional<ReviewJob> findByRunIdAndWorkspaceId(UUID runId, long workspaceId);

    /**
     * The fields of a review job that a sandbox request reads, without the capture. With a repository tree, the capture
     * lists the citable text of every captured file, megabytes that each request would otherwise parse and copy.
     */
    record ReviewJobCall(
            UUID id,
            long workspaceId,
            UUID reviewId,
            UUID runId,
            int attempt,
            @Nullable Instant executionStartedAt,
            JsonNode configSnapshot,
            ReviewJobInput input,
            @Nullable Integer admissionAttempt,
            @Nullable String admissionDigest) {
        /** Whether the job admitted results, as {@code ResultAdmissionService.isAdmitted} decides for the entity. */
        public boolean admitted() {
            return admissionAttempt != null && admissionDigest != null;
        }
    }

    String CALL = """
        SELECT new de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository$ReviewJobCall(
            j.id, j.workspaceId, j.reviewId, j.runId, j.attempt, j.executionStartedAt, j.configSnapshot, j.input,
            j.admissionAttempt, j.admissionDigest)
        FROM ReviewJob j
        """;

    @Query(CALL + "WHERE j.id = :id AND j.workspaceId = :workspaceId")
    Optional<ReviewJobCall> findCall(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    @Query(CALL + "WHERE j.runId = :runId AND j.workspaceId = :workspaceId")
    Optional<ReviewJobCall> findCallByRun(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    /** The composition claim that the run's job stored in its capture, read without the rest of the capture. */
    @NativeQuery("""
        SELECT j.id, (j.capture -> 'compositionClaim')::text
        FROM review_job j
        WHERE j.run_id = :runId AND j.workspace_id = :workspaceId
        """)
    Optional<CompositionClaim> findCompositionClaimByRun(
            @Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    record CompositionClaim(UUID id, @Nullable String claim) {}

    /** A job blocks a purge until it ends and settlement marks it DONE or FAILED. */
    @Query("SELECT CASE WHEN COUNT(j) > 0 THEN true ELSE false END FROM ReviewJob j "
            + "WHERE j.workspaceId = :workspaceId AND ("
            + "j.status IN ("
            + "de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.QUEUED, "
            + "de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.RUNNING) OR "
            + "j.processingStatus IN ("
            + "de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus.NONE, "
            + "de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus.PENDING))")
    boolean existsPurgeBlockingWork(@Param("workspaceId") Long workspaceId);

    /**
     * The practices of a job: each one is a session of the measurement with one model call in flight. A historical job
     * with no recorded assignments counts as one.
     */
    String PRACTICES = """
        CASE WHEN jsonb_typeof(j.input->'assignments')='array'
             THEN GREATEST(1, jsonb_array_length(j.input->'assignments')) ELSE 1 END""";

    /**
     * How many measurements a model pool admitted that still run, in every workspace that shares it, their practices,
     * the newest start, and the pool's limits.
     */
    interface PoolLoad {
        long getRunning();

        long getPractices();

        UUID[] getJobIds();

        @Nullable
        Instant getNewestStart();

        /** Null only for a pool that is gone. */
        @Nullable
        Integer getLearnedLimit();

        @Nullable
        Integer getMaxConcurrentCalls();
    }

    @WorkspaceAgnostic("A model pool is shared by the workspaces whose models it serves; the load names no workspace")
    @Query(
            value = "SELECT count(*) AS running, COALESCE(sum(" + PRACTICES + "), 0) AS practices," + """
         COALESCE(array_agg(j.id), CAST(ARRAY[] AS uuid[])) AS "jobIds",
         max(j.started_at) AS "newestStart",
          (SELECT p.learned_limit FROM llm_call_pool p WHERE p.id=:poolId) AS "learnedLimit",
          (SELECT p.max_concurrent_calls FROM llm_call_pool p WHERE p.id=:poolId) AS "maxConcurrentCalls"
        FROM review_job j
        JOIN agent_run r ON r.id=j.run_id AND r.workspace_id=j.workspace_id
        WHERE j.call_pool_id=:poolId AND j.status='RUNNING' AND r.kind='REVIEW' AND r.state='LEASED'
        """,
            nativeQuery = true)
    PoolLoad poolLoad(@Param("poolId") long poolId);

    /**
     * The model that a queued measurement froze at its submission: only the reference that names its model pool, read
     * from the job's snapshot. The rest of the snapshot stays in the database.
     */
    interface QueuedModel {
        UUID getJobId();

        UUID getRunId();

        @Nullable
        Integer getSchemaVersion();

        @Nullable
        String getConnectionScope();

        @Nullable
        Long getConnectionId();

        @Nullable
        Long getModelId();

        @Nullable
        Long getWorkspaceId();

        int getPractices();
    }

    String QUEUED_MODEL = "SELECT " + PRACTICES + " AS practices," + """
               j.id AS "jobId",
               j.run_id AS "runId", (j.config_snapshot->>'schemaVersion')::int AS "schemaVersion",
               j.config_snapshot->'model'->>'connectionScope' AS "connectionScope",
               (j.config_snapshot->'model'->>'connectionId')::bigint AS "connectionId",
               (j.config_snapshot->'model'->>'modelId')::bigint AS "modelId",
               (j.config_snapshot->'model'->>'workspaceId')::bigint AS "workspaceId"
          FROM review_job j
        """;

    /**
     * The models of due measurements whose sandbox has started but has not claimed the run yet. Each still takes its
     * pool's place: the pool load counts it only once its claim leases the run. A run that a hold or a backoff put back
     * to a later time is not due, so its idle sandbox takes no place. A deferral keeps the run due.
     */
    @WorkspaceAgnostic(
            "A model pool is shared by the workspaces whose models it serves; the read returns model references only")
    @Query(value = QUEUED_MODEL + """
        JOIN agent_run r ON r.id=j.run_id AND r.workspace_id=j.workspace_id
        JOIN workspace w ON w.id=r.workspace_id AND w.status='ACTIVE'
        JOIN agent_sandbox s ON s.workspace_id=r.workspace_id AND s.subject_key=r.concurrency_key
        WHERE j.status='QUEUED' AND r.kind='REVIEW' AND r.state='QUEUED' AND r.available_at <= clock_timestamp()
          AND (r.deadline IS NULL OR r.deadline > clock_timestamp()) AND s.state IN ('REQUESTED','READY')
        """, nativeQuery = true)
    List<QueuedModel> findStartingModels();

    /** The models of the queued jobs of these runs, read in one statement before the runs are claimed. */
    @WorkspaceAgnostic(
            "The dispatch reads a page of due runs of every workspace for their shared model pools; model references only")
    @Query(value = QUEUED_MODEL + """
        WHERE j.run_id IN (:runIds) AND j.status='QUEUED'
        """, nativeQuery = true)
    List<QueuedModel> findQueuedModels(@Param("runIds") Collection<UUID> runIds);

    @WorkspaceAgnostic("Bounded server completion pass; each selected job is reloaded under its own workspace")
    @Query(value = """
        SELECT j.id,j.workspace_id AS "workspaceId",j.review_id AS "reviewId"
        FROM review_job j
        WHERE j.status NOT IN ('QUEUED','RUNNING') AND j.processing_status IN ('NONE','PENDING')
        ORDER BY j.completed_at,j.id LIMIT :limit
        """, nativeQuery = true)
    List<CompletionCandidate> findTerminalForProcessing(@Param("limit") int limit);

    interface CompletionCandidate {
        UUID getId();

        long getWorkspaceId();

        UUID getReviewId();
    }

    @Query(
            value = "SELECT EXISTS(SELECT 1 FROM review_job WHERE workspace_id=:ws AND review_id=:review AND status"
                    + " NOT IN ('QUEUED','RUNNING') AND processing_status IN ('NONE','PENDING'))",
            nativeQuery = true)
    boolean hasUnsettledInvocation(@Param("ws") long workspaceId, @Param("review") UUID reviewId);

    List<ReviewJob> findByStatus(ReviewJobStatus status);

    /**
     * Takes the attempt's scan call lease when no live lease holds it: one model call of a scan attempt at a time, on
     * any server replica ({@code ScanCallLeases}).
     *
     * @return the job, when this call took the lease
     */
    @Transactional
    @Query(value = """
            UPDATE review_job SET scan_call_lease = :lease, scan_call_until = now() + :ttlSeconds * interval '1 second'
             WHERE id = :id AND workspace_id = :workspaceId
               AND (scan_call_lease IS NULL OR scan_call_until < now())
            RETURNING id
            """, nativeQuery = true)
    Optional<UUID> takeScanCallLease(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("lease") UUID lease,
            @Param("ttlSeconds") long ttlSeconds);

    /** Releases the lease, unless another call took it after it expired. */
    @Transactional
    @Query(value = """
            UPDATE review_job SET scan_call_lease = NULL, scan_call_until = NULL
             WHERE id = :id AND workspace_id = :workspaceId AND scan_call_lease = :lease
            RETURNING id
            """, nativeQuery = true)
    Optional<UUID> releaseScanCallLease(
            @Param("id") UUID id, @Param("workspaceId") long workspaceId, @Param("lease") UUID lease);

    /** Extends the leases that one replica's calls hold, by their jobs and their random lease ids. */
    @WorkspaceAgnostic("Renews only leases that this replica took; each random lease id names one job")
    @Transactional
    @Query(value = """
            UPDATE review_job SET scan_call_until = now() + :ttlSeconds * interval '1 second'
             WHERE id IN (:ids) AND scan_call_lease IN (:leases)
            RETURNING id
            """, nativeQuery = true)
    List<UUID> renewScanCallLeases(
            @Param("ids") Collection<UUID> ids,
            @Param("leases") Collection<UUID> leases,
            @Param("ttlSeconds") long ttlSeconds);

    /** The parent before the job, as the review service locks it before it changes the review; no parent state changes here. */
    @WorkspaceAgnostic("Run-based claim; parent join includes its workspace")
    @Query(value = """
            SELECT r.id FROM review r JOIN review_job j ON j.review_id=r.id AND j.workspace_id=r.workspace_id
            WHERE j.run_id=:runId AND j.status='QUEUED' FOR UPDATE OF r SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> lockParentForQueuedClaim(@Param("runId") UUID runId);

    /** Locks the queued job of a run that this claim leased; skipped when a lifecycle write holds it. */
    @Query(
            value = "SELECT j.id FROM review_job j WHERE j.run_id = :runId AND j.workspace_id = :workspaceId"
                    + " AND j.status = 'QUEUED' FOR UPDATE OF j SKIP LOCKED",
            nativeQuery = true)
    Optional<UUID> lockQueuedIdByRun(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    /**
     * The queued job of a run that this claim leased, locked; empty when a lifecycle write holds it. The lock and the
     * load are two statements because a native entity query cannot fill the attempt that the job reads from its run.
     */
    default Optional<ReviewJob> lockQueuedByRun(UUID runId, long workspaceId) {
        return lockQueuedIdByRun(runId, workspaceId).flatMap(this::findById);
    }

    /** The job that a run drives, unlocked: a fenced write then takes the job's locks in their order. */
    @Query("SELECT j.id FROM ReviewJob j WHERE j.runId = :runId AND j.workspaceId = :workspaceId")
    Optional<UUID> findIdByRun(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    /** The review locked before finding a completed job whose composition can resume. */
    @Query(value = """
            SELECT r.id FROM review r WHERE r.workspace_id=:workspaceId AND r.id=:reviewId FOR UPDATE
            """, nativeQuery = true)
    Optional<UUID> lockParentByReview(@Param("workspaceId") long workspaceId, @Param("reviewId") UUID reviewId);

    /** The parent of a run's job, locked before the job, as every writer of the review and its job takes them. */
    @WorkspaceAgnostic("Run-based lock; the run carries its workspace")
    @Query(value = """
            SELECT r.id FROM review r JOIN review_job j ON j.review_id=r.id AND j.workspace_id=r.workspace_id
            WHERE j.run_id=:runId AND j.workspace_id=:workspaceId FOR UPDATE OF r
            """, nativeQuery = true)
    Optional<UUID> lockParentByRun(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    /** The parent of a job, locked before the job, as every writer of the review and its job takes them. */
    @WorkspaceAgnostic("ID-based lock; the caller holds the job's run")
    @Query(value = """
            SELECT r.id FROM review r JOIN review_job j ON j.review_id=r.id AND j.workspace_id=r.workspace_id
            WHERE j.id=:jobId FOR UPDATE OF r
            """, nativeQuery = true)
    Optional<UUID> lockParentOfJob(@Param("jobId") UUID jobId);

    /** The job of a run, locked: the run's reaper takes it before it takes the run back. */
    @WorkspaceAgnostic("Run-based lock; the run carries its workspace")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ReviewJob j WHERE j.runId = :runId AND j.workspaceId = :workspaceId")
    Optional<ReviewJob> lockByRun(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    /** Holds the row through the status transition so a concurrent execution-start CAS cannot win afterward. */
    @WorkspaceAgnostic("ID-based locked recovery read; caller performs a fenced status transition")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ReviewJob j WHERE j.id = :id")
    Optional<ReviewJob> lockById(@Param("id") UUID id);

    /** @return rows updated (0 or 1); 0 means a concurrent transition won. */
    @WorkspaceAgnostic("ID-based status transition; job ID from workspace-scoped context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = :newStatus, j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status IN :fromStatuses")
    int transitionStatus(
            @Param("id") UUID id,
            @Param("newStatus") ReviewJobStatus newStatus,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("fromStatuses") Collection<ReviewJobStatus> fromStatuses);

    /**
     * Ends the RUNNING attempt like {@link #transitionStatus}, fenced on its run and attempt: a late terminal write of an
     * attempt whose run was requeued cannot end the attempt that runs now, on any worker.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = :newStatus, j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.runId = :runId AND j.attempt = :attempt")
    int transitionStatusOwnedBy(
            @Param("id") UUID id,
            @Param("newStatus") ReviewJobStatus newStatus,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt);

    /**
     * Unfenced by the run: a requester's cancel ends whichever attempt runs, and its run with it.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based cancel; job ID from worker-local drain or user-scoped admin call")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.CANCELLED, "
            + "j.completedAt = :now, j.errorMessage = :error, j.cancellationReason = :reason "
            + "WHERE j.id = :id AND j.status IN :fromStatuses")
    int transitionToCancelled(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("reason") ReviewJobCancellationReason reason,
            @Param("fromStatuses") Collection<ReviewJobStatus> fromStatuses);

    @WorkspaceAgnostic("ID-based execution-start fence; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.executionStartedAt = :now "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.executionStartedAt IS NULL "
            + "AND j.runId = :runId AND j.attempt = :attempt")
    int markExecutionStarted(
            @Param("id") UUID id, @Param("runId") UUID runId, @Param("attempt") int attempt, @Param("now") Instant now);

    /** Records only this run's descriptor; unrelated capture fields remain unchanged. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE review_job j SET capture=jsonb_set(COALESCE(j.capture,'{}'::jsonb),"
                    + "'{compositionClaim}',CAST(:snapshot AS jsonb)) FROM agent_run r"
                    + " WHERE j.id=:id AND j.workspace_id=:workspaceId AND j.run_id=:runId AND j.status='RUNNING'"
                    + " AND r.id=:runId AND r.workspace_id=:workspaceId AND " + ReviewRunKind.ATTEMPT_OF_RUN
                    + "=:attempt"
                    + " AND r.state='LEASED' AND r.lease_epoch=:epoch AND r.leased_until>clock_timestamp()",
            nativeQuery = true)
    int saveCompositionClaim(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt,
            @Param("epoch") int epoch,
            @Param("snapshot") String snapshot);

    @WorkspaceAgnostic("ID-based refusal transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = 'FAILED', j.completedAt = :now, j.output = :output "
            + "WHERE j.id = :id AND j.runId = :runId AND j.attempt = :attempt AND j.status = 'RUNNING'")
    int transitionToRefused(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt,
            @Param("now") Instant now,
            @Param("output") JsonNode output);

    /**
     * Ends the job of a run that a boundary refused because the workspace's access narrowed (ADR 0134). Its output tells
     * settlement why; admitted results stay. Fenced on the run, which the caller ends in the same transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = 'FAILED', j.completedAt = :now, j.errorMessage = :error, "
            + "j.output = :output WHERE j.id = :id AND j.workspaceId = :workspaceId AND j.runId = :runId "
            + "AND j.status IN ('RUNNING', 'QUEUED')")
    int failForAuthority(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("runId") UUID runId,
            @Param("now") Instant now,
            @Param("error") String error,
            @Param("output") JsonNode output);

    /** Ends an admitted attempt whose decisions nothing confirmed. Its output tells settlement why; its results stay. */
    @WorkspaceAgnostic("ID-based fenced terminal write; caller locks the run and its job")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = 'FAILED', j.completedAt = :now, j.output = :output "
            + "WHERE j.id = :id AND j.runId = :runId AND j.attempt = :attempt "
            + "AND j.admissionDigest IS NOT NULL AND j.status IN ('RUNNING', 'QUEUED')")
    int failCompositionUnconfirmed(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt,
            @Param("now") Instant now,
            @Param("output") JsonNode output);

    /** The current database time, including time spent waiting for a row lock in this transaction. */
    @WorkspaceAgnostic("Reads the database clock for attempt leases")
    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseClock();

    /** The database clock, which every lease compares with: the transaction's start, or this statement's without one. */
    @WorkspaceAgnostic("Reads the database clock")
    @Query(value = "SELECT now()", nativeQuery = true)
    Instant databaseNow();

    /**
     * The ended composition that delivery proof can resume, while its original, unerased capsule is retained: the
     * completed job, locked, with the run it ended on.
     */
    interface Resumable {
        UUID getId();

        UUID getRunId();

        int getEpoch();
    }

    @Query(value = """
        SELECT j.id, j.run_id AS "runId", r.lease_epoch AS epoch FROM review_job j JOIN agent_run r ON r.id=j.run_id
        WHERE j.workspace_id=:workspaceId AND j.review_id=:reviewId AND j.status='COMPLETED' AND j.admission_digest IS NOT NULL
          AND r.attempt<=:maxRetries
          AND EXISTS(SELECT 1 FROM review_composition_basis b WHERE b.workspace_id=j.workspace_id AND b.job_id=j.id
            AND b.expires_at>clock_timestamp())
          AND EXISTS(SELECT 1 FROM feedback f WHERE f.workspace_id=j.workspace_id AND f.review_id=j.review_id
            AND f.destination='ON_WORK' AND jsonb_exists(f.composition,'readback') AND NOT jsonb_exists(f.composition,'stop')
            AND NOT EXISTS(SELECT 1 FROM feedback newer WHERE newer.workspace_id=f.workspace_id AND newer.replaces_id=f.id))
        FOR UPDATE OF j
        """, nativeQuery = true)
    Optional<Resumable> lockResumableComposition(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("maxRetries") int maxRetries);

    /** Queues the resumed composition on the run that continues the ended one. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE review_job SET status='QUEUED', run_id=:runId, started_at=NULL, completed_at=NULL,
          execution_started_at=NULL
        WHERE id=:id AND workspace_id=:workspaceId AND status='COMPLETED'
        """, nativeQuery = true)
    int queueOnRun(@Param("id") UUID id, @Param("workspaceId") long workspaceId, @Param("runId") UUID runId);

    /**
     * Hands the running attempt back to the queue, keeping what it admitted and staged; its run is requeued in the same
     * transaction. The next attempt gets its own number when its claim takes the next epoch.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced requeue; the caller holds the attempt's run")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = 'QUEUED', j.startedAt = null, j.executionStartedAt = null"
            + " WHERE j.id = :id AND j.status = 'RUNNING' AND j.runId = :runId AND j.attempt = :attempt")
    int requeueRunning(@Param("id") UUID id, @Param("runId") UUID runId, @Param("attempt") int attempt);

    /**
     * Hands the running attempt's admitted job on to the run that composes it: the job waits on that run, keeping what
     * it admitted and staged. The caller ended the attempt's run and queued the next in the same transaction.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced hand-on; the caller holds the attempt's run")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = 'QUEUED', j.runId = :nextRunId, j.startedAt = null,"
            + " j.executionStartedAt = null"
            + " WHERE j.id = :id AND j.status = 'RUNNING' AND j.runId = :runId AND j.attempt = :attempt")
    int queueOnNextRun(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt,
            @Param("nextRunId") UUID nextRunId);

    /**
     * Hands the running attempt's admitted job on to the run that composes it, keeping the ended measuring run as the
     * job's history. A job hands off once: a second hand-off finds the column set and updates nothing.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced hand-off; the caller holds the attempt's run")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE review_job SET status = 'QUEUED', run_id = :nextRunId, handed_off_run_id = :runId,"
                    + " started_at = NULL, execution_started_at = NULL"
                    + " WHERE id = :id AND status = 'RUNNING' AND run_id = :runId AND handed_off_run_id IS NULL"
                    + " AND (SELECT " + ReviewRunKind.ATTEMPT_OF_RUN + " FROM agent_run r WHERE r.id = :runId)"
                    + " = :attempt",
            nativeQuery = true)
    int handOffToRun(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt,
            @Param("nextRunId") UUID nextRunId);

    /** Settlement reads the capture and output of a job that is not yet DONE or FAILED, so only a settled job loses them. */
    @WorkspaceAgnostic("Cross-workspace retention batch; caller is @WorkspaceAgnostic retention service")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE review_job SET capture = NULL, output = NULL " + "WHERE id IN ("
                    + "  SELECT id FROM review_job "
                    + "  WHERE status IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED') "
                    + "  AND completed_at < :cutoff "
                    + "  AND processing_status IN ('DONE','FAILED') "
                    + "  AND (capture IS NOT NULL OR output IS NOT NULL) "
                    + "  LIMIT :batchSize"
                    + ")",
            nativeQuery = true)
    int stripTerminalPayloads(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @WorkspaceAgnostic("Cross-workspace retention batch; caller is @WorkspaceAgnostic retention service")
    // A list result runs as a query, which neither flushes nor clears; Hibernate flushes before a native statement.
    @Query(
            value = "DELETE FROM review_job WHERE id IN (" + "  SELECT j.id FROM review_job j "
                    + "  WHERE j.status IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED') "
                    + "  AND j.completed_at < :cutoff "
                    + "  AND j.processing_status IN ('DONE','FAILED') "
                    + "  LIMIT :batchSize"
                    + ") RETURNING run_id AS \"runId\", handed_off_run_id AS \"handedOffRunId\"",
            nativeQuery = true)
    List<DeletedJob> deleteUnreferencedTerminalRowsOlderThan(
            @Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /** A deleted job's runs, which retention deletes in the same transaction. */
    interface DeletedJob {
        UUID getRunId();

        @Nullable
        UUID getHandedOffRunId();

        default List<UUID> runs() {
            UUID handedOff = getHandedOffRunId();
            return handedOff == null ? List.of(getRunId()) : List.of(getRunId(), handedOff);
        }
    }

    String NOT_ADMITTED = "j.admissionDigest IS NULL";

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ReviewJob j WHERE j.id = :id AND j.workspaceId = :workspaceId")
    Optional<ReviewJob> findByIdAndWorkspaceIdForUpdate(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.capture = :capture WHERE j.id = :id AND j.workspaceId = :workspaceId"
            + " AND j.runId = :runId AND j.attempt = :attempt AND j.status = 'RUNNING'")
    int updateCapture(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt,
            @Param("capture") JsonNode capture);

    /** Reads committed publication without returning a cached entity, under the caller's exact claim identity. */
    @Query(value = """
            SELECT CASE WHEN capture->>'runId'=CAST(:runId AS text)
                AND capture->>'attempt'=CAST(:attempt AS text)
                AND jsonb_typeof(capture->'program')='object' AND capture->>'programDigest' IS NOT NULL
                AND (execution_started_at IS NOT NULL OR capture->'program'->>'image' IS NULL)
                THEN true ELSE false END
            FROM review_job j JOIN agent_run r ON r.id=j.run_id AND r.workspace_id=j.workspace_id
            WHERE j.id=:id AND j.workspace_id=:workspaceId AND j.run_id=:runId AND (
            """ + ReviewRunKind.ATTEMPT_OF_RUN + """
            )=:attempt AND j.status='RUNNING' AND r.state='LEASED' AND r.leased_until>clock_timestamp()
            """, nativeQuery = true)
    Optional<Boolean> hasPublishedPreparation(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt);

    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE review_job SET capture = jsonb_set(COALESCE(capture, '{}'::jsonb), '{preparationFailure}',"
                    + " CAST(:diagnosis AS jsonb)) WHERE id = :id AND workspace_id = :workspaceId AND status ="
                    + " 'FAILED' AND run_id = :runId AND (SELECT " + ReviewRunKind.ATTEMPT_OF_RUN
                    + " FROM agent_run r WHERE r.id = run_id)"
                    + " = :attempt",
            nativeQuery = true)
    int recordPreparationFailure(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("attempt") int attempt,
            @Param("runId") UUID runId,
            @Param("diagnosis") String diagnosis);

    /**
     * Keeps a diagnosis on a running attempt's capture, under {@code key}: a review program's crash in its sandbox
     * ({@link RunnerEnd#PROGRAM_FAILURE_KEY}), which the error of a job whose retries run out names, or the server's own
     * error while it answered the attempt's results ({@link AttemptServerErrors#SERVER_ERROR_KEY}), which the attempt's
     * terminal error names.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    "UPDATE review_job SET capture = jsonb_set(COALESCE(capture, '{}'::jsonb), ARRAY[CAST(:key AS text)],"
                            + " CAST(:diagnosis AS jsonb)) WHERE id = :id AND workspace_id = :workspaceId AND status ="
                            + " 'RUNNING' AND run_id = :runId AND (SELECT " + ReviewRunKind.ATTEMPT_OF_RUN
                            + " FROM agent_run r WHERE r.id = run_id)"
                            + " = :attempt",
            nativeQuery = true)
    int recordDiagnosis(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("attempt") int attempt,
            @Param("runId") UUID runId,
            @Param("key") String key,
            @Param("diagnosis") String diagnosis);

    /** Its own transaction: a caller may run outside one. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE review_job SET capture = capture #- '{manifest,files}' #- '{manifest,citableTexts}' WHERE"
                    + " id = :jobId AND workspace_id = :workspaceId AND run_id = :runId"
                    + " AND (SELECT " + ReviewRunKind.ATTEMPT_OF_RUN
                    + " FROM agent_run r WHERE r.id = run_id) = :attempt AND"
                    + " admission_digest IS NOT NULL",
            nativeQuery = true)
    int expireCaptureInventory(
            @Param("jobId") UUID jobId,
            @Param("workspaceId") long workspaceId,
            @Param("attempt") int attempt,
            @Param("runId") UUID runId);
}
