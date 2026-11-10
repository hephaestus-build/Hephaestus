package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Repository
public interface ReviewJobRepository extends JpaRepository<ReviewJob, UUID> {
    List<ReviewJob> findByWorkspaceIdAndStatusInOrderByAvailableAtAscIdAsc(
            long workspaceId, Collection<ReviewJobStatus> statuses, Limit limit);

    /** The newest runs of the workspace since {@code since}, with only the columns a run's history shows. */
    List<RetainedAttempt> findAttemptsByWorkspaceIdAndAvailableAtGreaterThanEqualOrderByAvailableAtDesc(
            long workspaceId, Instant since, Pageable page);

    /** A run as its history shows it, without the configuration, capture and output it stored. */
    interface RetainedAttempt {
        UUID getId();

        UUID getReviewId();

        ReviewJobStatus getStatus();

        int getAttempt();

        ProcessingStatus getProcessingStatus();

        Instant getAvailableAt();

        @Nullable
        Instant getStartedAt();

        @Nullable
        Instant getCompletedAt();

        ReviewJobInput getInput();
    }

    List<ReviewJob> findByReviewIdAndWorkspaceIdOrderByAvailableAtAsc(UUID reviewId, long workspaceId);

    Optional<ReviewJob> findByReviewIdAndWorkspaceIdAndStatusIn(
            UUID reviewId, long workspaceId, Collection<ReviewJobStatus> statuses);

    boolean existsByReviewIdAndWorkspaceIdAndCancellationReason(
            UUID reviewId, long workspaceId, ReviewJobCancellationReason cancellationReason);

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

    /** The job that a run drives, in the run's workspace. */
    Optional<ReviewJob> findByRunIdAndWorkspaceId(UUID runId, long workspaceId);

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

    List<ReviewJob> findByStatusIn(Collection<ReviewJobStatus> statuses);

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

    @WorkspaceAgnostic("ID-based evidence-refusal transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(EVIDENCE_REFUSAL_TRANSITION)
    int transitionToEvidenceRefused(
            @Param("id") UUID id,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt,
            @Param("now") Instant now,
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
     * Cancels this worker's attempt for capacity: nothing of its sandbox ran, so it has no execution time either.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced cancel; caller is the claiming worker itself")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.CANCELLED, "
            + "j.completedAt = :now, j.errorMessage = :error, j.executionStartedAt = null, "
            + "j.cancellationReason = de.tum.cit.aet.hephaestus.agent.job.ReviewJobCancellationReason.NO_SANDBOX_ROOM "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.runId = :runId AND j.attempt = :attempt")
    int cancelForRoomOwnedBy(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("error") String error,
            @Param("runId") UUID runId,
            @Param("attempt") int attempt);

    /**
     * Cancels for capacity every queued review that needs a sandbox and is due: the sandbox runtime cannot hold it.
     * Admitted reviews that only compose need no sandbox and stay. A budget hold outlives its {@code availableAt} until
     * the next claim, and such a review waits for money, not room, so it stays too. Several workers may run it; the
     * second changes nothing.
     *
     * @return the runs of the reviews cancelled, which the caller ends
     */
    @WorkspaceAgnostic("Fleet-wide capacity verdict over the shared queue; caller is a worker's admission check")
    // A list result runs as a query, which neither flushes nor clears; Hibernate flushes before a native statement.
    @Query(value = """
            UPDATE review_job j SET status = 'CANCELLED', completed_at = :now, error_message = :error,
                   cancellation_reason = 'NO_SANDBOX_ROOM'
              FROM agent_run r
             WHERE r.id = j.run_id AND j.status = 'QUEUED' AND r.available_at <= clock_timestamp()
               AND j.admission_digest IS NULL AND (r.hold_reason IS NULL OR r.hold_reason <> 'BUDGET')
            RETURNING j.run_id
            """, nativeQuery = true)
    List<UUID> cancelQueuedForRoom(@Param("now") Instant now, @Param("error") String error);

    /** Whether a queued review still needs a sandbox: one held for its budget waits for money, not room. */
    @WorkspaceAgnostic("Fleet-wide capacity verdict over the shared queue; caller is a worker's admission check")
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM review_job j JOIN agent_run r ON r.id = j.run_id
             WHERE j.status = 'QUEUED' AND j.admission_digest IS NULL
               AND (r.hold_reason IS NULL OR r.hold_reason <> 'BUDGET'))
            """, nativeQuery = true)
    boolean existsQueuedForRoom();

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
                    + ") RETURNING run_id",
            nativeQuery = true)
    List<UUID> deleteUnreferencedTerminalRowsOlderThan(
            @Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Depth, oldest-eligible-age, held count and running count in a single pass, so the scan cost does
     * not multiply when the backlog grows. {@code :now} is the sampler's clock; a run's {@code available_at} is
     * the database's, so a skew of the two moves a job across the edge of {@code depth} for that long.
     *
     * <p>{@code depth} counts only jobs a worker could claim right now, which is what "are we keeping
     * up?" means — a job on a retry backoff is not work the fleet is behind on. {@code held} is counted
     * separately for the case that distinction hides: a workspace over its LLM cap has its whole backlog
     * pushed out of {@code depth}, so depth alone reads identically to an idle instance. Held is the
     * number that separates paused from idle.
     */
    @WorkspaceAgnostic("Fleet-wide queue-health snapshot; caller is @WorkspaceAgnostic health sampler")
    @Query(
            value = "SELECT COUNT(*) FILTER (WHERE j.status = 'QUEUED' AND r.available_at <= :now) AS depth,"
                    + " MIN(r.available_at) FILTER (WHERE j.status = 'QUEUED' AND r.available_at <= :now)"
                    + " AS oldestAvailableAt,"
                    + " COUNT(*) FILTER (WHERE j.status = 'QUEUED' AND r.hold_reason = 'BUDGET') AS held,"
                    + " COUNT(*) FILTER (WHERE j.status = 'RUNNING') AS running"
                    + " FROM review_job j JOIN agent_run r ON r.id = j.run_id WHERE j.status IN ('QUEUED', 'RUNNING')",
            nativeQuery = true)
    QueueHealthSnapshot queueHealthSnapshot(@Param("now") Instant now);

    /** {@code oldestAvailableAt} is null when nothing is eligible. */
    interface QueueHealthSnapshot {
        long getDepth();

        @Nullable
        Instant getOldestAvailableAt();

        /** QUEUED jobs parked on a hold an admin can lift, as opposed to a retry backoff. */
        long getHeld();

        long getRunning();
    }

    String NOT_ADMITTED = "j.admissionDigest IS NULL";
    String EVIDENCE_REFUSAL_TRANSITION =
            "UPDATE ReviewJob j SET j.status = 'FAILED', j.completedAt = :now, j.output = :output "
                    + "WHERE j.id = :id AND j.runId = :runId AND j.attempt = :attempt AND j.status = 'RUNNING'";

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

    /** The sandbox initializer's failure of the last attempt, beside a preparation failure: for operators only. */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    "UPDATE review_job SET capture = jsonb_set(COALESCE(capture, '{}'::jsonb), '{initializationFailure}',"
                            + " CAST(:diagnosis AS jsonb)) WHERE id = :id AND workspace_id = :workspaceId AND status ="
                            + " 'FAILED' AND run_id = :runId AND (SELECT " + ReviewRunKind.ATTEMPT_OF_RUN
                            + " FROM agent_run r WHERE r.id = run_id)"
                            + " = :attempt",
            nativeQuery = true)
    int recordInitializationFailure(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("attempt") int attempt,
            @Param("runId") UUID runId,
            @Param("diagnosis") String diagnosis);

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
