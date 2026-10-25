package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
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

    @Query("SELECT CASE WHEN COUNT(j) > 0 THEN true ELSE false END FROM ReviewJob j "
            + "WHERE j.workspaceId = :workspaceId AND ("
            + "j.status IN ("
            + "de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.QUEUED, "
            + "de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.RUNNING) OR "
            + "(j.status = de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.COMPLETED AND "
            + "j.processingStatus = de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus.PENDING))")
    boolean existsPurgeBlockingWork(@Param("workspaceId") Long workspaceId);

    @Query(value = """
        SELECT COUNT(*) FROM review_job
        WHERE workspace_id = :workspaceId AND status = 'RUNNING'
          AND COALESCE(config_snapshot -> 'model' ->> 'dataHandlingTier', 'UNDECLARED') = :#{#tier.name()}
        """, nativeQuery = true)
    long countRunningByWorkspaceIdAndDataHandlingTier(Long workspaceId, DataHandlingTier tier);

    /** Migrated invocations retain their processing history, not work for the current completion pass. */
    @WorkspaceAgnostic("Bounded server completion pass; each selected job is reloaded under its own workspace")
    @Query(value = """
        SELECT j.id,j.workspace_id AS "workspaceId",j.review_id AS "reviewId"
        FROM review_job j
        WHERE j.status NOT IN ('QUEUED','RUNNING') AND j.processing_status IN ('NONE','PENDING')
          AND NOT EXISTS (
              SELECT 1 FROM review r
              WHERE r.id=j.review_id AND r.workspace_id=j.workspace_id AND r.id=j.id
                AND jsonb_exists(r.checks,'MIGRATION')
          )
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
     * Clears the hold a budget block placed on this workspace's queued jobs, so raising the cap takes
     * effect immediately. Scoped to {@code hold_reason = 'BUDGET'} rather than "any future
     * {@code available_at}" so it cannot fast-forward a crash-retry backoff.
     *
     * @return how many held jobs were released
     */
    @WorkspaceAgnostic("Workspace-scoped release; caller is the budget writer for that workspace")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.availableAt = :now, j.holdReason = null "
            + "WHERE j.workspaceId = :workspaceId AND j.status = 'QUEUED' AND j.holdReason = 'BUDGET'")
    int releaseBudgetHolds(@Param("workspaceId") Long workspaceId, @Param("now") Instant now);

    /** Rechecks eligibility under the row lock, using the same application clock that set available_at. */
    @WorkspaceAgnostic("ID-based claim; job ID from a workspace-scoped candidate poll")
    @Query(
            value = "SELECT j.* FROM review_job j JOIN workspace w ON w.id = j.workspace_id "
                    + "WHERE j.id = :id AND j.status = 'QUEUED' AND j.available_at <= :now AND w.status = 'ACTIVE' "
                    + "FOR UPDATE OF j SKIP LOCKED",
            nativeQuery = true)
    Optional<ReviewJob> findByIdQueuedForUpdateSkipLocked(@Param("id") UUID id, @Param("now") Instant now);

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
     * Ends the RUNNING attempt like {@link #transitionStatus}, fenced on the worker and the attempt: a late terminal write
     * of an attempt that was orphan-requeued cannot end the attempt that runs now, on a sibling or on this same worker.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = :newStatus, j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.workerId = :workerId AND j.attempt = :attempt")
    int transitionStatusOwnedBy(
            @Param("id") UUID id,
            @Param("newStatus") ReviewJobStatus newStatus,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("workerId") String workerId,
            @Param("attempt") int attempt);

    /**
     * Unfenced by worker; callers that know the owning worker should prefer
     * {@link #transitionToCancelledOwnedBy}.
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

    /** The state of a job row that a usage settlement holds: whether the call's attempt is still the running one. */
    interface UsageOwner {
        String getStatus();

        int getAttempt();

        UUID getReviewId();
    }

    /**
     * Takes the job row for a usage settlement. Every settlement takes the job row before the attempt's
     * {@link ReviewAttemptUsage} row, whether it settles a call or the end of an attempt, so the two never wait on
     * each other in opposite orders. Empty when the job no longer exists in that workspace.
     */
    @Query(
            value = "SELECT j.status AS status, j.attempt AS attempt, j.review_id AS \"reviewId\" FROM review_job j"
                    + " WHERE j.id = :id AND j.workspace_id = :workspaceId FOR UPDATE",
            nativeQuery = true)
    Optional<UsageOwner> lockUsageOwner(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /**
     * Holds the job row while the proxy registers a call, and only while the call's attempt still runs on its
     * worker. A terminal transition waits for the registration, and the registration waits for the transition.
     */
    @Query(
            value = "SELECT j.attempt FROM review_job j WHERE j.id = :id AND j.workspace_id = :workspaceId AND"
                    + " j.attempt = :attempt AND j.status = 'RUNNING' AND j.worker_id IS NOT DISTINCT FROM"
                    + " CAST(:workerId AS varchar) FOR SHARE",
            nativeQuery = true)
    Optional<Integer> lockRunningAttemptForCall(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("attempt") int attempt,
            @Param("workerId") @Nullable String workerId);

    /**
     * Adds one proxied call's tokens to the running totals of the attempt that runs now. These counters are what
     * the proxy's in-flight budget reads while the attempt runs. A clean finish overwrites them with the totals the
     * ledger selected. The attempt's own measurement is its {@link ReviewAttemptUsage} row, which the requeue does
     * not zero.
     *
     * <p>Fenced on {@code attempt} and {@code RUNNING}: {@link #requeueOrphan} may hand the row to a new attempt
     * (zeroing these columns) while the proxy is still waiting on the provider, and the new attempt's in-flight
     * spend must not include an earlier attempt's call.
     *
     * @param attempt the {@code attempt} the caller observed when it authenticated the call
     * @return 1 if the attempt still owns the row, 0 if it has been superseded (a safe no-op)
     */
    @WorkspaceAgnostic("ID-based per-call usage accumulation from the worker-local proxy")
    @Modifying(flushAutomatically = true)
    @Query("UPDATE ReviewJob j SET " + "j.llmCalls = COALESCE(j.llmCalls, 0) + 1, "
            + "j.llmInputTokens = COALESCE(j.llmInputTokens, 0) + :#{#usage.inputTokens()}, "
            + "j.llmOutputTokens = COALESCE(j.llmOutputTokens, 0) + :#{#usage.outputTokens()}, "
            + "j.llmReasoningTokens = COALESCE(j.llmReasoningTokens, 0) + :#{#usage.reasoningTokens()}, "
            + "j.llmCacheReadTokens = COALESCE(j.llmCacheReadTokens, 0) + :#{#usage.cacheReadTokens()}, "
            + "j.llmCacheWriteTokens = COALESCE(j.llmCacheWriteTokens, 0) + :#{#usage.cacheWriteTokens()} "
            + "WHERE j.id = :id AND j.attempt = :attempt AND j.status = 'RUNNING'")
    int accumulateLlmUsage(
            @Param("id") UUID id, @Param("attempt") int attempt, @Param("usage") ReviewJobLlmUsageDelta usage);

    /**
     * Adds one proxied practice script call to the row of its attempt, kind, and practice, creating the row on
     * the first call. The attempt froze one model per kind, so the first call's tier is the tier of every
     * call. The workspace comes from the job row, never from the caller.
     *
     * <p>The attempt is part of the key, so a call of an earlier attempt adds to that attempt's row and never to the
     * current one. A call that returns after its attempt ended still lands here: {@link ReviewAttemptUsageService}
     * holds the job row and then projects the attempt's ledger rows again. The job must not be on an earlier attempt
     * than the call, and the practice must exist in the job's workspace.
     *
     * @return 1 when the call was added, 0 when the job or the practice does not exist
     */
    @WorkspaceAgnostic("ID-based per-call usage accumulation from the worker-local proxy; the job row supplies it")
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO review_job_script_usage
            (job_id, attempt, model_kind, practice_id, workspace_id, data_handling_tier, calls, reported_calls,
             input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, search_units)
        SELECT j.id, :attempt, :#{#call.modelKindName()}, p.id, j.workspace_id,
            CAST(:#{#call.dataHandlingTierName()} AS varchar), 1, :#{#call.usageReported() ? 1 : 0}, :#{#call.inputTokens()},
            :#{#call.outputTokens()}, :#{#call.cacheReadTokens()}, :#{#call.cacheWriteTokens()},
            :#{#call.searchUnits()}
        FROM review_job j
        JOIN practice p ON p.workspace_id = j.workspace_id AND p.slug = :#{#call.practiceSlug()}
        WHERE j.id = :jobId AND j.attempt >= :attempt
        ON CONFLICT (job_id, attempt, model_kind, practice_id) DO UPDATE SET
            calls = review_job_script_usage.calls + 1,
            reported_calls = review_job_script_usage.reported_calls + EXCLUDED.reported_calls,
            input_tokens = review_job_script_usage.input_tokens + EXCLUDED.input_tokens,
            output_tokens = review_job_script_usage.output_tokens + EXCLUDED.output_tokens,
            cache_read_tokens = review_job_script_usage.cache_read_tokens
                + EXCLUDED.cache_read_tokens,
            cache_write_tokens = review_job_script_usage.cache_write_tokens
                + EXCLUDED.cache_write_tokens,
            search_units = review_job_script_usage.search_units + EXCLUDED.search_units
        """, nativeQuery = true)
    int accumulatePracticeScriptUsage(
            @Param("jobId") UUID jobId, @Param("attempt") int attempt, @Param("call") PracticeScriptCallUsage call);

    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.job.PracticeScriptKindTotal("
            + "u.id.modelKind, SUM(u.calls), SUM(u.inputTokens), SUM(u.outputTokens), SUM(u.cacheReadTokens), "
            + "SUM(u.cacheWriteTokens), SUM(u.searchUnits), SUM(u.reportedCalls)) "
            + "FROM ScriptUsage u "
            + "WHERE u.workspaceId = :workspaceId AND u.id.jobId = :jobId AND u.id.attempt = :attempt "
            + "GROUP BY u.id.modelKind ORDER BY u.id.modelKind")
    List<PracticeScriptKindTotal> sumPracticeScriptUsageByKind(
            @Param("workspaceId") Long workspaceId, @Param("jobId") UUID jobId, @Param("attempt") int attempt);

    /**
     * Reads the totals straight from the row rather than from a possibly stale in-memory entity, so
     * committed proxy accumulations are included.
     */
    @WorkspaceAgnostic("ID-based usage read; job ID from worker-local terminal accounting")
    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.job.ReviewJobLlmUsage("
            + "COALESCE(j.llmCalls, 0), COALESCE(j.llmInputTokens, 0), "
            + "COALESCE(j.llmOutputTokens, 0), COALESCE(j.llmReasoningTokens, 0), "
            + "COALESCE(j.llmCacheReadTokens, 0), COALESCE(j.llmCacheWriteTokens, 0)) "
            + "FROM ReviewJob j WHERE j.id = :id")
    Optional<ReviewJobLlmUsage> findLlmUsageById(@Param("id") UUID id);

    /**
     * Cancels the RUNNING attempt like {@link #transitionToCancelled}, fenced on the owning worker and the attempt: a
     * draining worker must not cancel a later attempt if the job was orphan-requeued out from under it.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced cancel; job ID + owner from worker-local drain context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.CANCELLED, "
            + "j.completedAt = :now, j.errorMessage = :error, j.cancellationReason = :reason "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.workerId = :workerId AND j.attempt = :attempt")
    int transitionToCancelledOwnedBy(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("reason") ReviewJobCancellationReason reason,
            @Param("workerId") String workerId,
            @Param("attempt") int attempt);

    @WorkspaceAgnostic("ID-based execution-start fence; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.executionStartedAt = :now "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.executionStartedAt IS NULL "
            + "AND j.workerId = :workerId AND j.attempt = :attempt")
    int markExecutionStarted(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("attempt") int attempt,
            @Param("now") Instant now);

    @WorkspaceAgnostic("ID-based evidence-refusal transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(EVIDENCE_REFUSAL_TRANSITION)
    int transitionToEvidenceRefused(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("attempt") int attempt,
            @Param("now") Instant now,
            @Param("output") JsonNode output);

    /**
     * Poll-loop candidates, id-only because {@link #findByIdQueuedForUpdateSkipLocked} re-checks and
     * locks each one; a stale read here costs at most a skipped candidate.
     *
     * <p>Candidates whose slot is already at its {@code max_concurrent_jobs} cap are excluded. A slot is a
     * {@code (workspace, purpose, AI tier)} binding, the tier being the one the job's snapshot froze and
     * {@code UNDECLARED} where it names none, as {@link #countRunningByWorkspaceIdAndDataHandlingTier}
     * and the claim count it. Without the exclusion, one saturated slot with a deep backlog fills every LIMIT
     * window with jobs nobody can claim, and a younger runnable job elsewhere never reaches the batch. A
     * candidate whose slot has no binding row is still fetched — the claim's admission re-check is the
     * authoritative gate.
     */
    @WorkspaceAgnostic("Cross-workspace poll candidates; caller is the @WorkspaceAgnostic job poller")
    @Query(
            value = "SELECT j.id FROM review_job j JOIN workspace w ON w.id = j.workspace_id WHERE j.status = 'QUEUED'"
                    + " AND w.status = 'ACTIVE' AND j.available_at <= now() AND (  (SELECT count(*) FROM review_job r     "
                    + " WHERE r.workspace_id = j.workspace_id AND r.status = 'RUNNING'        AND"
                    + " COALESCE(r.config_snapshot -> 'model' ->> 'dataHandlingTier', 'UNDECLARED')          ="
                    + " COALESCE(j.config_snapshot -> 'model' ->> 'dataHandlingTier', 'UNDECLARED'))   < COALESCE((SELECT"
                    + " b.max_concurrent_jobs FROM workspace_agent_binding b      WHERE b.workspace_id = j.workspace_id"
                    + " AND b.purpose = 'PRACTICE_REVIEW'        AND b.data_handling_tier = COALESCE(j.config_snapshot ->"
                    + " 'model' ->> 'dataHandlingTier', 'UNDECLARED')),      2147483647)) ORDER BY j.available_at ASC,"
                    + " j.id ASC LIMIT :limit",
            nativeQuery = true)
    List<UUID> findQueuedIdsOldestFirst(@Param("limit") int limit);

    @WorkspaceAgnostic("Cross-workspace stale job reaper; caller is @WorkspaceAgnostic sweeper")
    @Query("SELECT j FROM ReviewJob j WHERE j.status = 'RUNNING' AND j.startedAt < :cutoff")
    List<ReviewJob> findStaleRunningJobs(@Param("cutoff") Instant cutoff);

    /** Compare heartbeat times on the database clock so application clock skew cannot create false orphans. */
    @WorkspaceAgnostic("Cross-workspace orphan recovery; caller is @WorkspaceAgnostic sweeper")
    @Query(
            value = "SELECT j.id AS jobId, j.workspace_id AS workspaceId, j.attempt AS attempt, "
                    + "j.worker_id AS workerId "
                    + "FROM review_job j WHERE j.status = 'RUNNING' AND j.worker_id IS NOT NULL "
                    + "AND j.started_at < :graceCutoff "
                    + "AND NOT EXISTS (SELECT 1 FROM worker_registry w WHERE w.worker_id = j.worker_id "
                    + "AND w.last_heartbeat >= now() - make_interval(secs => :leaseTtlSeconds))",
            nativeQuery = true)
    List<OrphanedJobRef> findOrphanedRunningJobs(
            @Param("graceCutoff") Instant graceCutoff, @Param("leaseTtlSeconds") long leaseTtlSeconds);

    /**
     * Locks this exact owned attempt, as erasure's cancellation locks it before marking receipts, and finds it only
     * while it can be continued: no saved output, an admission recorded with its attempt, and that admission's kept
     * inputs current, unexpired and vouched for by the admitting attempt's READY receipt that indexes every person
     * they copied. A receipt erased, purged, unfinished or of another attempt finds nothing. Erasure that begins after
     * this lock cancels the requeued attempt; one that marked the receipt before it leaves the attempt to fail.
     */
    @WorkspaceAgnostic("ID-based fenced lookup; the basis and receipt joins pin the job's own workspace")
    @Query(value = """
        SELECT j.id FROM review_job j
        JOIN review_composition_basis b ON b.job_id = j.id AND b.workspace_id = j.workspace_id
        JOIN person_evidence_copy c
          ON c.id = b.copy_id AND c.job_id = j.id AND c.workspace_id = j.workspace_id
        WHERE j.id = :id AND j.status = 'RUNNING' AND j.worker_id = :workerId AND j.attempt = :attempt
          AND j.output IS NULL
          AND b.basis_version = :version AND b.expires_at > :now AND b.admission_attempt <= j.attempt
          AND b.admission_digest = j.admission_digest
          AND j.admission_attempt = b.admission_attempt
          AND c.payload ->> 'attempt' = CAST(b.admission_attempt AS text)
        FOR NO KEY UPDATE OF j
        """, nativeQuery = true)
    List<UUID> lockContinuableAttempt(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("attempt") int attempt,
            @Param("version") int version,
            @Param("now") Instant now);

    @WorkspaceAgnostic("ID-based fenced requeue; caller is @WorkspaceAgnostic sweeper or worker-local drain")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = 'QUEUED', j.workerId = null, j.startedAt = null, j.executionStartedAt ="
            + " null, j.attempt = j.attempt + 1, j.availableAt = :#{#request.availableAt}, j.llmCalls = 0,"
            + " j.llmInputTokens = 0, j.llmOutputTokens = 0, j.llmReasoningTokens = 0, j.llmCacheReadTokens = 0,"
            + " j.llmCacheWriteTokens = 0 WHERE j.id = :id AND j.status = 'RUNNING' AND j.workerId = :workerId AND"
            + " j.attempt = :#{#request.attempt} AND j.attempt < :#{#request.maxRetries} AND"
            + " j.output IS NULL AND NOT (" + NOT_ADMITTED + ") "
            + "AND EXISTS (SELECT 1 FROM ReviewCompositionBasis b WHERE b.jobId = j.id "
            + "AND b.workspaceId = j.workspaceId AND b.expiresAt > :#{#request.now} "
            + "AND b.admissionDigest = j.admissionDigest)")
    int requeueLockedForComposition(
            @Param("id") UUID id, @Param("workerId") String workerId, @Param("request") CompositionRequeue request);

    record CompositionRequeue(int attempt, int maxRetries, Instant now, Instant availableAt) {}

    /** Ends an owned admitted attempt without discarding proof; admitted attempts must not be requeued. */
    @WorkspaceAgnostic("ID-based fenced terminal write; caller is @WorkspaceAgnostic sweeper or worker-local drain")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus.FAILED, "
            + "j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.workerId = :workerId AND j.attempt = :attempt "
            + "AND NOT (" + NOT_ADMITTED + ")")
    int failAdmittedOwnedBy(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("attempt") int attempt,
            @Param("now") Instant now,
            @Param("error") String error);

    /** Pool rejection leaves attempt, availability, and token unchanged because no sandbox executed. */
    @WorkspaceAgnostic("ID-based self-fenced requeue; caller is the claiming worker itself")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = 'QUEUED', j.workerId = null, j.startedAt = null, "
            + "j.executionStartedAt = null "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND (:workerId IS NULL OR j.workerId = :workerId)")
    int requeueRejectedClaim(@Param("id") UUID id, @Param("workerId") @Nullable String workerId);

    /** Pending delivery recovery still reads output; stripping it would make delivery permanently unrecoverable. */
    @WorkspaceAgnostic("Cross-workspace retention batch; caller is @WorkspaceAgnostic retention service")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE review_job SET capture = NULL, output = NULL " + "WHERE id IN ("
                    + "  SELECT id FROM review_job "
                    + "  WHERE status IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED') "
                    + "  AND completed_at < :cutoff "
                    + "  AND processing_status <> 'PENDING' "
                    + "  AND (capture IS NOT NULL OR output IS NOT NULL) "
                    + "  LIMIT :batchSize"
                    + ")",
            nativeQuery = true)
    int stripTerminalPayloads(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @WorkspaceAgnostic("Cross-workspace retention batch; caller is @WorkspaceAgnostic retention service")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM review_job WHERE id IN (" + "  SELECT j.id FROM review_job j "
                    + "  WHERE j.status IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED') "
                    + "  AND j.completed_at < :cutoff "
                    + "  AND j.processing_status <> 'PENDING' "
                    + "  LIMIT :batchSize"
                    + ")",
            nativeQuery = true)
    int deleteUnreferencedTerminalRowsOlderThan(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Depth, oldest-eligible-age, held count and running count in a single pass, so the scan cost does
     * not multiply when the backlog grows. {@code :now} is bound rather than
     * read from the DB clock, matching {@link #findByIdQueuedForUpdateSkipLocked}.
     *
     * <p>{@code depth} counts only jobs a worker could claim right now, which is what "are we keeping
     * up?" means — a job on a retry backoff is not work the fleet is behind on. {@code held} is counted
     * separately for the case that distinction hides: a workspace over its LLM cap has its whole backlog
     * pushed out of {@code depth}, so depth alone reads identically to an idle instance. Held is the
     * number that separates paused from idle.
     */
    @WorkspaceAgnostic("Fleet-wide queue-health snapshot; caller is @WorkspaceAgnostic health sampler")
    @Query(
            value = "SELECT   COUNT(*) FILTER (WHERE status = 'QUEUED' AND available_at <= :now) AS depth,  "
                    + " MIN(available_at) FILTER (WHERE status = 'QUEUED' AND available_at <= :now) AS oldestAvailableAt, "
                    + "  COUNT(*) FILTER (WHERE status = 'QUEUED' AND hold_reason IS NOT NULL) AS held,   COUNT(*) FILTER"
                    + " (WHERE status = 'RUNNING') AS running FROM review_job WHERE status IN ('QUEUED', 'RUNNING')",
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
                    + "WHERE j.id = :id AND j.workerId = :workerId AND j.attempt = :attempt AND j.status = 'RUNNING'";

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ReviewJob j WHERE j.id = :id AND j.workspaceId = :workspaceId")
    Optional<ReviewJob> findByIdAndWorkspaceIdForUpdate(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.capture = :capture WHERE j.id = :id AND j.workspaceId = :workspaceId"
            + " AND j.workerId = :workerId AND j.attempt = :attempt AND j.status = 'RUNNING'")
    int updateCapture(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("workerId") @Nullable String workerId,
            @Param("attempt") int attempt,
            @Param("capture") JsonNode capture);

    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE review_job SET capture = jsonb_set(COALESCE(capture, '{}'::jsonb), '{preparationFailure}',"
                    + " CAST(:diagnosis AS jsonb)) WHERE id = :id AND workspace_id = :workspaceId AND status ="
                    + " 'FAILED' AND attempt = :attempt AND (CAST(:workerId AS text) IS NULL OR worker_id ="
                    + " :workerId)",
            nativeQuery = true)
    int recordPreparationFailure(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("attempt") int attempt,
            @Param("workerId") @Nullable String workerId,
            @Param("diagnosis") String diagnosis);

    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE review_job SET capture = capture #- '{manifest,files}' #- '{manifest,citableTexts}' WHERE"
                    + " id = :jobId AND workspace_id = :workspaceId AND attempt = :attempt AND worker_id = :workerId AND"
                    + " admission_digest IS NOT NULL",
            nativeQuery = true)
    int expireCaptureInventory(
            @Param("jobId") UUID jobId,
            @Param("workspaceId") long workspaceId,
            @Param("attempt") int attempt,
            @Param("workerId") String workerId);

    @WorkspaceAgnostic("The worker supplies the claimed job id and ownership fence")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReviewJob j SET j.status = 'QUEUED', j.workerId = null, j.startedAt = null, j.executionStartedAt ="
            + " null, j.attempt = j.attempt + 1, j.availableAt = :availableAt,"
            + " j.llmCalls = 0, j.llmInputTokens = 0, j.llmOutputTokens = 0, j.llmReasoningTokens = 0,"
            + " j.llmCacheReadTokens = 0, j.llmCacheWriteTokens = 0 WHERE j.id = :id AND j.status = 'RUNNING' AND"
            + " j.workerId = :workerId AND j.attempt < :maxRetries AND j.admissionDigest IS NULL")
    int requeueOrphan(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("maxRetries") int maxRetries,
            @Param("availableAt") Instant availableAt);
}
