package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

@Repository
public interface AgentJobRepository extends JpaRepository<AgentJob, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentJob j WHERE j.workspace.id = :workspaceId")
    int deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /**
     * One page of a workspace's jobs, with an optional status filter, selecting only what the listing
     * renders. An entity page would carry {@code container_logs} — a whole review transcript per row,
     * which no listing reads — into heap and throw it away. {@code output} stays: the listing renders
     * it and derives each run's outcome from it, and it is bounded by the agent's result contract
     * rather than by how much a review had to say.
     */
    @Query("SELECT j.id AS id, j.jobType AS jobType, j.status AS status, j.integrationKind AS integrationKind, "
            + "j.metadata AS metadata, j.output AS output, j.configSnapshot AS configSnapshot, "
            + "j.exitCode AS exitCode, j.errorMessage AS errorMessage, j.deliveryStatus AS deliveryStatus, "
            + "j.deliveryCommentId AS deliveryCommentId, j.retryCount AS retryCount, "
            + "j.availableAt AS availableAt, j.holdReason AS holdReason, j.createdAt AS createdAt, "
            + "j.startedAt AS startedAt, j.completedAt AS completedAt, j.llmModel AS llmModel, "
            + "j.llmModelVersion AS llmModelVersion, j.llmTotalCalls AS llmTotalCalls, "
            + "j.llmTotalInputTokens AS llmTotalInputTokens, j.llmTotalOutputTokens AS llmTotalOutputTokens, "
            + "j.llmTotalReasoningTokens AS llmTotalReasoningTokens, j.llmCacheReadTokens AS llmCacheReadTokens, "
            + "j.llmCacheWriteTokens AS llmCacheWriteTokens "
            + "FROM AgentJob j WHERE j.workspace.id = :workspaceId "
            + "AND (:status IS NULL OR j.status = :status)")
    Page<AgentJobListRow> findListRows(
            @Param("workspaceId") Long workspaceId,
            @Param("status") @Nullable AgentJobStatus status,
            Pageable pageable);

    @Query("SELECT j.id AS id, j.jobType AS jobType, j.integrationKind AS integrationKind, j.metadata AS metadata "
            + "FROM AgentJob j WHERE j.workspace.id = :workspaceId AND j.id IN :ids")
    List<ReviewRunTargetRow> findReviewRunTargets(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    /**
     * What these runs decided, for the trace view. {@code reviewReadiness} is the per-practice record
     * and is deliberately fetched with them: a run that ends without measuring a practice looks the
     * same from {@code status} and {@code output} whatever the cause, and only readiness says which.
     */
    @Query("SELECT j.id AS id, j.status AS status, j.output AS output, j.reviewReadiness AS reviewReadiness, "
            + "j.completedAt AS completedAt FROM AgentJob j WHERE j.workspace.id = :workspaceId AND j.id IN :ids")
    List<ReviewOutcomeRow> findReviewOutcomes(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    interface ReviewOutcomeRow {
        UUID getId();

        AgentJobStatus getStatus();

        @Nullable
        JsonNode getOutput();

        @Nullable
        JsonNode getReviewReadiness();

        @Nullable
        Instant getCompletedAt();
    }

    /**
     * One page of review runs, narrowed by any combination of status and a {@code createdAt} window.
     *
     * <p>Every filter is independently optional; a null one drops out of the predicate. The window is
     * inclusive at {@code from} and exclusive at {@code to}, the same half-open convention the
     * observation and feedback listings use, so a day picked in both surfaces means the same day.
     *
     * <p>The one query replaced a pair of overloads that differed only in the status clause — a third
     * and fourth filter would have needed four. {@code CAST(:from AS Instant)} is what lets Hibernate
     * type a null bound; see {@code AuthEventRepository#findForAdmin}, which is allowlisted out of the
     * parameter-count arch rule for exactly this reason.
     */
    @Query("SELECT j.id AS id, j.status AS status, j.jobType AS jobType, j.integrationKind AS integrationKind, "
            + "j.metadata AS metadata, j.createdAt AS createdAt FROM AgentJob j "
            + "WHERE j.workspace.id = :workspaceId AND j.purpose = :purpose "
            + "AND (:status IS NULL OR j.status = :status) "
            + "AND (CAST(:from AS Instant) IS NULL OR j.createdAt >= :from) "
            + "AND (CAST(:to AS Instant) IS NULL OR j.createdAt < :to)")
    Page<ReviewRunSummaryRow> findReviewRunSummaries(
            @Param("workspaceId") Long workspaceId,
            @Param("purpose") AgentPurpose purpose,
            @Param("status") @Nullable AgentJobStatus status,
            @Param("from") @Nullable Instant from,
            @Param("to") @Nullable Instant to,
            Pageable pageable);

    @Query("SELECT j FROM AgentJob j JOIN FETCH j.workspace WHERE j.id = :id AND j.workspace.id = :workspaceId")
    Optional<AgentJob> findByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /**
     * Which evidence contract governed a run, without reading the snapshot it is recorded in.
     *
     * <p>A snapshot carries one entry per staged file, so a repository-tree capture makes it megabytes,
     * and Postgres has no partial read for a TOASTed jsonb. Loading the job to take one string out of it
     * therefore detoasts, ships and parses the whole document — and evidence authorization asks this
     * question once per observation, on surfaces that list hundreds. Extracting the key in SQL keeps the
     * detoast on the server and the answer to a few bytes.
     *
     * @return the contract version, or empty when this workspace has no such run or the run recorded no
     *     evidence — both of which mean nothing may be cited from it
     */
    @Query(
            value = "SELECT jsonb_extract_path_text(j.evidence_snapshot, 'manifest', 'contractVersion') "
                    + "FROM agent_job j WHERE j.id = :id AND j.workspace_id = :workspaceId",
            nativeQuery = true)
    Optional<String> findEvidenceContractVersion(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /**
     * The same answer as {@link #findEvidenceContractVersion} for a whole set of runs, in one round trip.
     *
     * <p>Evidence authorization asks the question once per observation, and the surfaces that ask it list
     * hundreds — a developer with forty pull requests across ten practices costs four hundred sequential
     * round trips on one dashboard read. The per-row query is cheap; the latency is the count.
     *
     * <p>A run this workspace does not own yields no row, and a run that recorded no evidence yields a row
     * whose value is {@code null}. Both mean nothing may be cited from it, exactly as the empty
     * {@link Optional} does on the single-row query.
     */
    @Query(value = """
        SELECT j.id AS "id",
               jsonb_extract_path_text(j.evidence_snapshot, 'manifest', 'contractVersion') AS "contractVersion"
        FROM agent_job j
        WHERE j.id IN :ids
          AND j.workspace_id = :workspaceId
        """, nativeQuery = true)
    List<EvidenceContractVersionRow> findEvidenceContractVersions(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    interface EvidenceContractVersionRow {
        UUID getId();

        @Nullable
        String getContractVersion();
    }

    @Query("SELECT CASE WHEN COUNT(j) > 0 THEN true ELSE false END FROM AgentJob j "
            + "WHERE j.workspace.id = :workspaceId AND ("
            + "j.status IN ("
            + "de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.QUEUED, "
            + "de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.RUNNING) OR "
            + "(j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.COMPLETED AND "
            + "j.deliveryStatus = de.tum.cit.aet.hephaestus.agent.job.DeliveryStatus.PENDING))")
    boolean existsPurgeBlockingWork(@Param("workspaceId") Long workspaceId);

    @Query(value = """
            SELECT COUNT(*) FROM agent_job
            WHERE workspace_id = :workspaceId AND purpose = :#{#purpose.name()} AND status = 'RUNNING'
              AND COALESCE(config_snapshot ->> 'dataHandlingTier', 'UNDECLARED') = :#{#tier.name()}
            """, nativeQuery = true)
    long countRunningByWorkspaceIdAndPurposeAndDataHandlingTier(
            Long workspaceId, AgentPurpose purpose, DataHandlingTier tier);

    long countByWorkspaceIdAndPurposeAndCreatedAtGreaterThanEqual(
            Long workspaceId, AgentPurpose purpose, Instant createdAt);

    List<AgentJob> findByStatus(AgentJobStatus status);

    List<AgentJob> findByStatusIn(Collection<AgentJobStatus> statuses);

    Optional<AgentJob> findByJobTokenHashAndStatus(String jobTokenHash, AgentJobStatus status);

    /**
     * Clears the hold a budget block placed on this workspace's queued jobs, so raising the cap takes
     * effect immediately. Scoped to {@code hold_reason = 'BUDGET'} rather than "any future
     * {@code available_at}" so it cannot fast-forward a crash-retry backoff.
     *
     * @return how many held jobs were released
     */
    @WorkspaceAgnostic("Workspace-scoped release; caller is the budget writer for that workspace")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.availableAt = :now, j.holdReason = null "
            + "WHERE j.workspace.id = :workspaceId AND j.status = 'QUEUED' AND j.holdReason = 'BUDGET'")
    int releaseBudgetHolds(@Param("workspaceId") Long workspaceId, @Param("now") Instant now);

    Optional<AgentJob> findByWorkspaceIdAndIdempotencyKeyAndStatusIn(
            Long workspaceId, String idempotencyKey, Collection<AgentJobStatus> statuses);

    Optional<AgentJob> findByWorkspaceIdAndIdempotencyKey(Long workspaceId, String idempotencyKey);

    /**
     * Matches on an idempotency-key prefix, so a caller can look across the varying tail of the key
     * (head SHA, revision, timestamp) for an earlier review of the same subject.
     */
    @Query("SELECT j FROM AgentJob j WHERE j.workspace.id = :workspaceId"
            + " AND j.idempotencyKey LIKE :keyPrefix ESCAPE '\\'"
            + " AND j.createdAt > :cutoff"
            + " ORDER BY j.createdAt DESC"
            + " LIMIT 1")
    Optional<AgentJob> findRecentJobByKeyPrefix(
            @Param("workspaceId") Long workspaceId,
            @Param("keyPrefix") String keyPrefix,
            @Param("cutoff") Instant cutoff);

    /**
     * Empty if a concurrent poller holds the row, or it is no longer eligible. Eligibility is
     * re-checked here and not only in the candidate poll, because a concurrent backoff-requeue can push
     * {@code available_at} into the future in between. {@code :now} is bound rather than read from the
     * DB clock so eligibility is judged against the same app clock that computed {@code available_at}.
     */
    @WorkspaceAgnostic("ID-based claim; job ID from a workspace-scoped candidate poll")
    @Query(
            value = "SELECT j.* FROM agent_job j JOIN workspace w ON w.id = j.workspace_id "
                    + "WHERE j.id = :id AND j.status = 'QUEUED' AND j.available_at <= :now AND w.status = 'ACTIVE' "
                    + "FOR UPDATE OF j SKIP LOCKED",
            nativeQuery = true)
    Optional<AgentJob> findByIdQueuedForUpdateSkipLocked(@Param("id") UUID id, @Param("now") Instant now);

    @WorkspaceAgnostic("ID-based reload; job ID from workspace-scoped claim context")
    @Query("SELECT j FROM AgentJob j LEFT JOIN FETCH j.workspace WHERE j.id = :id")
    Optional<AgentJob> findByIdWithWorkspace(@Param("id") UUID id);

    /**
     * The row lock makes this read and the caller's following status transition one decision: a
     * concurrent execution-start CAS either commits before this read or waits and then loses because
     * the job is no longer RUNNING.
     */
    @WorkspaceAgnostic("ID-based locked recovery read; caller performs a fenced status transition")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM AgentJob j LEFT JOIN FETCH j.workspace WHERE j.id = :id")
    Optional<AgentJob> findByIdWithWorkspaceForUpdate(@Param("id") UUID id);

    /** @return rows updated (0 or 1); 0 means a concurrent transition won. */
    @WorkspaceAgnostic("ID-based status transition; job ID from workspace-scoped context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = :newStatus, j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status IN :fromStatuses")
    int transitionStatus(
            @Param("id") UUID id,
            @Param("newStatus") AgentJobStatus newStatus,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("fromStatuses") Collection<AgentJobStatus> fromStatuses);

    /**
     * Like {@link #transitionStatus}, but a worker whose job was orphan-requeued to a sibling cannot
     * clobber the sibling's run with its own late terminal write.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = :newStatus, j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status IN :fromStatuses AND j.workerId = :workerId")
    int transitionStatusOwnedBy(
            @Param("id") UUID id,
            @Param("newStatus") AgentJobStatus newStatus,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("fromStatuses") Collection<AgentJobStatus> fromStatuses,
            @Param("workerId") String workerId);

    /**
     * Unfenced by worker; callers that know the owning worker should prefer
     * {@link #transitionToCancelledOwnedBy}.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based cancel; job ID from worker-local drain or user-scoped admin call")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.CANCELLED, "
            + "j.completedAt = :now, j.errorMessage = :error, j.cancellationReason = :reason "
            + "WHERE j.id = :id AND j.status IN :fromStatuses")
    int transitionToCancelled(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("error") String error,
            @Param("reason") AgentJobCancellationReason reason,
            @Param("fromStatuses") Collection<AgentJobStatus> fromStatuses);

    /**
     * Adds one proxied call's tokens to the running totals of ONE attempt, so a job that crashes
     * mid-run still has the calls it made on record. A clean finish overwrites these with the runner's
     * authoritative totals.
     *
     * <p>Fenced on {@code retry_count} and {@code RUNNING} because a provider call can outlive the
     * attempt that issued it: {@link #requeueOrphan} may hand the row to a new attempt (zeroing these
     * columns) while the proxy is still waiting on the provider, and a late write would otherwise bill
     * one attempt's tokens at another attempt's frozen price and funding source.
     *
     * @param attempt the {@code retry_count} the caller observed when it authenticated the call
     * @return 1 if the attempt still owns the row, 0 if it has been superseded (a safe no-op)
     */
    @WorkspaceAgnostic("ID-based per-call usage accumulation from the worker-local proxy")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET " + "j.llmTotalCalls = COALESCE(j.llmTotalCalls, 0) + 1, "
            + "j.llmTotalInputTokens = COALESCE(j.llmTotalInputTokens, 0) + :#{#usage.inputTokens()}, "
            + "j.llmTotalOutputTokens = COALESCE(j.llmTotalOutputTokens, 0) + :#{#usage.outputTokens()}, "
            + "j.llmTotalReasoningTokens = COALESCE(j.llmTotalReasoningTokens, 0) + :#{#usage.reasoningTokens()}, "
            + "j.llmCacheReadTokens = COALESCE(j.llmCacheReadTokens, 0) + :#{#usage.cacheReadTokens()}, "
            + "j.llmCacheWriteTokens = COALESCE(j.llmCacheWriteTokens, 0) + :#{#usage.cacheWriteTokens()} "
            + "WHERE j.id = :id AND j.retryCount = :attempt AND j.status = 'RUNNING'")
    int accumulateLlmUsage(
            @Param("id") UUID id, @Param("attempt") int attempt, @Param("usage") AgentJobLlmUsageDelta usage);

    /**
     * Reads the totals straight from the row rather than from a possibly stale in-memory entity, so
     * committed proxy accumulations are included.
     */
    @WorkspaceAgnostic("ID-based usage read; job ID from worker-local terminal accounting")
    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.job.AgentJobLlmUsage("
            + "COALESCE(j.llmTotalCalls, 0), COALESCE(j.llmTotalInputTokens, 0), "
            + "COALESCE(j.llmTotalOutputTokens, 0), COALESCE(j.llmTotalReasoningTokens, 0), "
            + "COALESCE(j.llmCacheReadTokens, 0), COALESCE(j.llmCacheWriteTokens, 0)) "
            + "FROM AgentJob j WHERE j.id = :id")
    Optional<AgentJobLlmUsage> findLlmUsageById(@Param("id") UUID id);

    /**
     * Completed pull-request and issue reviews with unfinished preparation in {@code [from, until)}.
     * A successful empty result has a completion mark and is excluded.
     */
    @WorkspaceAgnostic("Cross-tenant recovery sweep over jobs whose feedback lanes have no completion mark")
    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.job.UnpreparedFeedbackLanes("
            + "j.id, j.workspace.id, j.inChatPreparedAt, j.inAppPreparedAt) FROM AgentJob j "
            + "WHERE j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.COMPLETED "
            + "AND j.jobType IN (de.tum.cit.aet.hephaestus.agent.AgentJobType.PULL_REQUEST_REVIEW, "
            + "de.tum.cit.aet.hephaestus.agent.AgentJobType.ISSUE_REVIEW) "
            + "AND j.completedAt >= :from AND j.completedAt < :until "
            + "AND (j.inChatPreparedAt IS NULL OR j.inAppPreparedAt IS NULL) "
            + "ORDER BY j.completedAt")
    List<UnpreparedFeedbackLanes> findUnpreparedFeedbackLanes(
            @Param("from") Instant from, @Param("until") Instant until, Pageable pageable);

    /**
     * Records successful preparation, including an empty result, without overwriting its first completion time.
     *
     * @return 1 if recorded, 0 if already recorded or the job no longer exists
     */
    @WorkspaceAgnostic("ID-based lane completion mark; job ID from the lane's own event or the recovery sweep")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.inChatPreparedAt = :at WHERE j.id = :id AND j.inChatPreparedAt IS NULL")
    int markInChatPrepared(@Param("id") UUID id, @Param("at") Instant at);

    /** The in-app lane's half of {@link #markInChatPrepared}. */
    @WorkspaceAgnostic("ID-based lane completion mark; job ID from the lane's own event or the recovery sweep")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.inAppPreparedAt = :at WHERE j.id = :id AND j.inAppPreparedAt IS NULL")
    int markInAppPrepared(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Like {@link #transitionToCancelled}, fenced to the owning worker: a draining worker must not
     * cancel a sibling's run if the job was orphan-requeued out from under it.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced cancel; job ID + owner from worker-local drain context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.CANCELLED, "
            + "j.completedAt = :now, j.errorMessage = :error, j.cancellationReason = :reason "
            + "WHERE j.id = :id AND j.status IN :fromStatuses AND j.workerId = :workerId")
    int transitionToCancelledOwnedBy(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("error") String error,
            @Param("reason") AgentJobCancellationReason reason,
            @Param("fromStatuses") Collection<AgentJobStatus> fromStatuses,
            @Param("workerId") String workerId);

    /** Persists the accounting boundary immediately before sandbox/provider execution. */
    @WorkspaceAgnostic("ID-based execution-start fence; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.executionStartedAt = :now "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.executionStartedAt IS NULL "
            + "AND ((:workerId IS NULL AND j.workerId IS NULL) OR j.workerId = :workerId)")
    int markExecutionStarted(
            @Param("id") UUID id, @Param("workerId") @Nullable String workerId, @Param("now") Instant now);

    /** Written before the sandbox starts, so a failed or cancelled run still records what it consumed. */
    @WorkspaceAgnostic("ID-based provenance stamp; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.promptDigest = :#{#stamp.promptDigest}, "
            + "j.inputsDigest = :#{#stamp.inputsDigest}, "
            + "j.evidenceSnapshot = :#{#stamp.evidenceSnapshot}, "
            + "j.reviewReadiness = :#{#stamp.reviewReadiness} "
            + "WHERE j.id = :id AND j.status = 'RUNNING' "
            + "AND ((:workerId IS NULL AND j.workerId IS NULL) OR j.workerId = :workerId) "
            + "AND j.retryCount = :retryCount")
    int updateProvenanceDigests(
            @Param("id") UUID id,
            @Param("workerId") @Nullable String workerId,
            @Param("retryCount") int retryCount,
            @Param("stamp") ProvenanceStamp stamp);

    /** Written as a unit so the evidence snapshot and the readiness decisions over it cannot diverge. */
    record ProvenanceStamp(
            @Nullable String promptDigest,
            @Nullable String inputsDigest,
            @Nullable JsonNode evidenceSnapshot,
            @Nullable JsonNode reviewReadiness) {}

    @WorkspaceAgnostic("ID-based evidence-refusal transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = 'COMPLETED', j.completedAt = :now, j.output = :output, "
            + "j.errorMessage = NULL WHERE j.id = :id AND j.status = 'RUNNING' "
            + "AND ((:workerId IS NULL AND j.workerId IS NULL) OR j.workerId = :workerId) "
            + "AND j.retryCount = :retryCount")
    int transitionToEvidenceRefused(
            @Param("id") UUID id,
            @Param("workerId") @Nullable String workerId,
            @Param("retryCount") int retryCount,
            @Param("now") Instant now,
            @Param("output") JsonNode output);

    /**
     * Poll-loop candidates, id-only because {@link #findByIdQueuedForUpdateSkipLocked} re-checks and
     * locks each one; a stale read here costs at most a skipped candidate.
     *
     * <p>Candidates whose {@code (workspace, purpose)} binding is already at its
     * {@code max_concurrent_jobs} cap are excluded. Without that, one saturated workspace-purpose with
     * a deep backlog fills every LIMIT window with jobs nobody can claim, and a younger runnable job
     * elsewhere never reaches the batch. A candidate with no binding row is still fetched — the claim's
     * admission re-check is the authoritative gate.
     */
    @WorkspaceAgnostic("Cross-workspace poll candidates; caller is the @WorkspaceAgnostic job poller")
    @Query(
            value = "SELECT j.id FROM agent_job j JOIN workspace w ON w.id = j.workspace_id "
                    + "WHERE j.status = 'QUEUED' "
                    + "AND w.status = 'ACTIVE' "
                    + "AND j.available_at <= now() "
                    + "AND ("
                    + "  (SELECT count(*) FROM agent_job r "
                    + "     WHERE r.workspace_id = j.workspace_id AND r.purpose = j.purpose AND r.status = 'RUNNING') "
                    + "  < COALESCE((SELECT b.max_concurrent_jobs FROM workspace_agent_binding b "
                    + "     WHERE b.workspace_id = j.workspace_id AND b.purpose = j.purpose), 2147483647)"
                    + ") "
                    + "ORDER BY j.available_at ASC, j.id ASC LIMIT :limit",
            nativeQuery = true)
    List<UUID> findQueuedIdsOldestFirst(@Param("limit") int limit);

    @WorkspaceAgnostic("Cross-workspace stale job reaper; caller is @WorkspaceAgnostic sweeper")
    @Query("SELECT j FROM AgentJob j WHERE j.status = 'RUNNING' AND j.startedAt < :cutoff")
    List<AgentJob> findStaleRunningJobs(@Param("cutoff") Instant cutoff);

    /**
     * RUNNING jobs whose owning worker has no fresh heartbeat. Native so the liveness comparison stays
     * on the DB clock on both sides ({@code last_heartbeat} is written with the DB {@code now()}), and
     * no app/DB skew can produce a false orphan.
     */
    @WorkspaceAgnostic("Cross-workspace orphan recovery; caller is @WorkspaceAgnostic sweeper")
    @Query(
            value = "SELECT j.id AS jobId, j.workspace_id AS workspaceId, j.retry_count AS retryCount, "
                    + "j.worker_id AS workerId "
                    + "FROM agent_job j WHERE j.status = 'RUNNING' AND j.worker_id IS NOT NULL "
                    + "AND j.started_at < :graceCutoff "
                    + "AND NOT EXISTS (SELECT 1 FROM worker_registry w WHERE w.worker_id = j.worker_id "
                    + "AND w.last_heartbeat >= now() - make_interval(secs => :leaseTtlSeconds))",
            nativeQuery = true)
    List<OrphanedJobRef> findOrphanedRunningJobs(
            @Param("graceCutoff") Instant graceCutoff, @Param("leaseTtlSeconds") long leaseTtlSeconds);

    /**
     * CAS requeue of an orphaned or draining job: RUNNING → QUEUED, ownership cleared, retry_count
     * bumped.
     *
     * <p>Fenced on {@code worker_id}: status alone would let a belated requeue steal a job that a
     * different worker has since legitimately re-claimed. The retry cap is in the WHERE clause too, so
     * a caller that forgets to check it cannot requeue past the cap.
     *
     * <p>Rotates the job token so a merely partitioned zombie sandbox cannot keep authenticating
     * against the LLM proxy once a sibling re-claims the row, and zeroes the per-attempt LLM
     * accumulators: the caller bills the dead attempt before requeuing, so leaving the totals would
     * make the next attempt's terminal billing record the overlap a second time.
     *
     * @param availableAt when the requeued job becomes claimable again (now + backoff, so a
     *     crash-looping job cannot burn its whole retry budget in seconds)
     * @param newJobToken freshly generated plaintext token (encrypted at rest by the entity's converter)
     * @param newJobTokenHash {@code AgentJob.computeTokenHash(newJobToken)} — indexed lookup hash
     * @return 1 if this caller won the race, 0 otherwise
     */
    @WorkspaceAgnostic("ID-based orphan/drain requeue; caller is @WorkspaceAgnostic sweeper or worker-local drain")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = 'QUEUED', j.workerId = null, "
            + "j.startedAt = null, j.executionStartedAt = null, "
            + "j.retryCount = j.retryCount + 1, j.availableAt = :availableAt, "
            + "j.jobToken = :newJobToken, j.jobTokenHash = :newJobTokenHash, "
            + "j.llmTotalCalls = 0, j.llmTotalInputTokens = 0, j.llmTotalOutputTokens = 0, "
            + "j.llmTotalReasoningTokens = 0, j.llmCacheReadTokens = 0, j.llmCacheWriteTokens = 0 "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.workerId = :workerId AND j.retryCount < :maxRetries")
    int requeueOrphan(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("maxRetries") int maxRetries,
            @Param("availableAt") Instant availableAt,
            @Param("newJobToken") String newJobToken,
            @Param("newJobTokenHash") String newJobTokenHash);

    /**
     * Requeue of a claim this same worker just won but could not dispatch (sandbox executor pool
     * rejection). The job never started, so {@code retry_count} is left untouched — otherwise an
     * undersized sandbox pool would exhaust {@code max-retries} on jobs that never ran. {@code
     * available_at} is left untouched too: the row could only have been claimed because it was already
     * in the past, so it stays immediately reclaimable. No token rotation — no sandbox ever saw it.
     * {@code :workerId} is null only when the worker role runs with no identity configured.
     *
     * @return 1 if requeued, 0 if the row is no longer RUNNING-and-ours; the caller treats both the
     *     same, since either way the claim is gone
     */
    @WorkspaceAgnostic("ID-based self-fenced requeue; caller is the claiming worker itself")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = 'QUEUED', j.workerId = null, j.startedAt = null, "
            + "j.executionStartedAt = null "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND (:workerId IS NULL OR j.workerId = :workerId)")
    int requeueRejectedClaim(@Param("id") UUID id, @Param("workerId") @Nullable String workerId);

    @WorkspaceAgnostic("ID-based delivery update; job ID from workspace-scoped delivery context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.deliveryStatus = :status, j.deliveryCommentId = :commentId " + "WHERE j.id = :id")
    void updateDeliveryStatus(
            @Param("id") UUID id,
            @Param("status") DeliveryStatus status,
            @Param("commentId") @Nullable String commentId);

    @WorkspaceAgnostic("Dispatch recovery carries both the tenant id and job id")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE AgentJob j SET j.deliveryStatus = :status, j.deliveryCommentId = :commentId "
            + "WHERE j.id = :id AND j.workspace.id = :workspaceId")
    int reconcileDispatchDeliveryStatus(
            @Param("id") UUID id,
            @Param("workspaceId") Long workspaceId,
            @Param("status") DeliveryStatus status,
            @Param("commentId") @Nullable String commentId);

    /** @return 1 if transitioned, 0 if the row no longer matches the expected job/delivery statuses. */
    @WorkspaceAgnostic("ID-based delivery transition; job ID from workspace-scoped context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.deliveryStatus = :newStatus "
            + "WHERE j.id = :id AND j.status = 'COMPLETED' AND j.deliveryStatus IN :fromStatuses")
    int transitionDeliveryStatus(
            @Param("id") UUID id,
            @Param("newStatus") DeliveryStatus newStatus,
            @Param("fromStatuses") Collection<DeliveryStatus> fromStatuses);

    /**
     * Bounded by {@code pageable} so one sweep pass never loads an unbounded backlog, and narrowed to
     * what the sweep decides on: most candidates are skipped or exhausted, and only the one that wins
     * its attempt is worth reading whole.
     */
    @WorkspaceAgnostic("Cross-workspace delivery-recovery sweep; caller is @WorkspaceAgnostic sweeper")
    @Query("SELECT j.id AS id, j.deliveryAttempts AS deliveryAttempts, j.deliveryCommentId AS deliveryCommentId "
            + "FROM AgentJob j WHERE j.status = 'COMPLETED' AND j.deliveryStatus = 'PENDING' "
            + "AND j.completedAt < :cutoff ORDER BY j.completedAt ASC")
    List<StuckDeliveryRow> findStuckPendingDeliveries(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * The one candidate a sweep pass won its attempt on, read whole because the delivery needs the
     * output the review produced. The status and delivery predicates repeat the sweep's own, so a row
     * that finished or failed in between is simply not there.
     */
    @WorkspaceAgnostic("ID-based read of a delivery-recovery candidate; job ID from the @WorkspaceAgnostic sweep")
    @Query("SELECT j FROM AgentJob j WHERE j.id = :id AND j.status = 'COMPLETED' " + "AND j.deliveryStatus = 'PENDING'")
    Optional<AgentJob> findDeliveryRecoveryCandidate(@Param("id") UUID id);

    /**
     * Increments {@code delivery_attempts} only if it still matches {@code expectedAttempts}, so two
     * concurrent sweeper passes cannot both re-post the same stuck delivery. The winner (1) proceeds
     * with the redelivery; a loser (0) skips this pass.
     */
    @WorkspaceAgnostic("ID-based delivery-recovery CAS; job ID from workspace-scoped sweep candidate")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.deliveryAttempts = j.deliveryAttempts + 1 "
            + "WHERE j.id = :id AND j.status = 'COMPLETED' AND j.deliveryStatus = 'PENDING' "
            + "AND j.deliveryAttempts = :expectedAttempts")
    int claimDeliveryRecoveryAttempt(@Param("id") UUID id, @Param("expectedAttempts") short expectedAttempts);

    /**
     * Terminal write for a delivery-recovery attempt, fenced on the {@code delivery_attempts} value the
     * caller's own {@link #claimDeliveryRecoveryAttempt} claimed. That counter is not a lease, so a slow
     * attempt spanning several sweep passes can be superseded by a later one; without the fence,
     * whichever finished last would win and a stale FAILED could clobber a DELIVERED.
     *
     * @return 1 if this attempt's result was recorded; 0 if a later attempt superseded it — treat as
     *     lost and do not retry the write
     */
    @WorkspaceAgnostic("ID-based fenced delivery-recovery terminal write; job ID from workspace-scoped sweep candidate")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.deliveryStatus = :newStatus, j.deliveryCommentId = :commentId "
            + "WHERE j.id = :id AND j.status = 'COMPLETED' AND j.deliveryStatus IN :fromStatuses "
            + "AND j.deliveryAttempts = :expectedAttempts")
    int transitionDeliveryStatusFenced(
            @Param("id") UUID id,
            @Param("newStatus") DeliveryStatus newStatus,
            @Param("commentId") @Nullable String commentId,
            @Param("fromStatuses") Collection<DeliveryStatus> fromStatuses,
            @Param("expectedAttempts") short expectedAttempts);

    /**
     * Strips the heavy payload columns from terminal rows, batched so a large backlog is worked off in
     * many short transactions. Idempotent.
     *
     * <p>Excludes {@code delivery_status = 'PENDING'}: the delivery-recovery retry reads
     * {@code output}, so stripping it first would make a stuck delivery permanently undeliverable.
     */
    @WorkspaceAgnostic("Cross-workspace retention batch; caller is @WorkspaceAgnostic retention service")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE agent_job SET container_logs = NULL, output = NULL " + "WHERE id IN ("
                    + "  SELECT id FROM agent_job "
                    + "  WHERE status IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED') "
                    + "  AND completed_at < :cutoff "
                    + "  AND delivery_status <> 'PENDING' "
                    + "  AND (container_logs IS NOT NULL OR output IS NOT NULL) "
                    + "  LIMIT :batchSize"
                    + ")",
            nativeQuery = true)
    int stripTerminalPayloads(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @WorkspaceAgnostic("Cross-workspace retention batch; caller is @WorkspaceAgnostic retention service")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM agent_job WHERE id IN (" + "  SELECT j.id FROM agent_job j "
                    + "  WHERE j.status IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED') "
                    + "  AND j.completed_at < :cutoff "
                    + "  AND j.delivery_status <> 'PENDING' "
                    + "  AND NOT EXISTS (SELECT 1 FROM feedback f WHERE f.agent_job_id = j.id) "
                    + "  AND NOT EXISTS (SELECT 1 FROM observation o WHERE o.agent_job_id = j.id) "
                    + "  LIMIT :batchSize"
                    + ")",
            nativeQuery = true)
    int deleteUnreferencedTerminalRowsOlderThan(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Depth, oldest-eligible-age, held count and running count in a single pass, so the scan cost does
     * not multiply exactly when an incident has inflated the backlog. {@code :now} is bound rather than
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
            value = "SELECT " + "  COUNT(*) FILTER (WHERE status = 'QUEUED' AND available_at <= :now) AS depth, "
                    + "  MIN(available_at) FILTER (WHERE status = 'QUEUED' AND available_at <= :now) AS oldestAvailableAt, "
                    + "  COUNT(*) FILTER (WHERE status = 'QUEUED' AND hold_reason IS NOT NULL) AS held, "
                    + "  COUNT(*) FILTER (WHERE status = 'RUNNING') AS running "
                    + "FROM agent_job WHERE status IN ('QUEUED', 'RUNNING')",
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

    /**
     * Per-practice readiness outcomes over the most recent reviews of a workspace.
     *
     * <p>One statement, because two would take two snapshots under {@code READ COMMITTED} and a review
     * completing between them shifts the window: counts and blocking reasons would then describe
     * different sets of jobs. The {@code id} tiebreaker keeps the window stable when a sync enqueues
     * several jobs in the same microsecond.
     *
     * <p>Native because the decisions live in a JSONB column and the useful shape is one row per
     * decision, which needs {@code jsonb_array_elements}.
     */
    @Query(
            value = "WITH recent AS (" + "  SELECT j.id, j.review_readiness FROM agent_job j"
                    + "   WHERE j.workspace_id = :workspaceId AND j.review_readiness IS NOT NULL"
                    + "   ORDER BY j.created_at DESC, j.id DESC LIMIT :window"
                    + "), decision AS ("
                    + "  SELECT d FROM recent,"
                    + "   jsonb_array_elements(recent.review_readiness -> 'decisions') d"
                    + "), counted AS ("
                    + "  SELECT d ->> 'practiceSlug' AS practice_slug,"
                    + "         count(*) AS considered,"
                    + "         count(*) FILTER (WHERE (d ->> 'ready')::boolean) AS reviewed"
                    + "    FROM decision GROUP BY 1"
                    + "), blocked AS ("
                    +
                    // A decision can be skipped without any source failing: the author set the practice to a
                    // mode that runs no model. Those carry a decision-level reason and no failing check, so
                    // reporting only source failures would leave the skip with no stated cause at all.
                    "  SELECT d ->> 'practiceSlug' AS practice_slug,"
                    + "         c ->> 'sourceKind' AS source_kind,"
                    + "         reason AS reason_code,"
                    + "         count(*) AS reviews"
                    + "    FROM decision,"
                    + "         jsonb_array_elements(d -> 'sourceChecks') c,"
                    + "         jsonb_array_elements_text(c -> 'reasonCodes') reason"
                    + "   WHERE NOT (d ->> 'ready')::boolean AND NOT (c ->> 'meetsRequirements')::boolean"
                    + "   GROUP BY 1, 2, 3"
                    + "   UNION ALL"
                    + "  SELECT d ->> 'practiceSlug', NULL, reason, count(*)"
                    + "    FROM decision, jsonb_array_elements_text(d -> 'reasonCodes') reason"
                    + "   WHERE NOT (d ->> 'ready')::boolean"
                    + "   GROUP BY 1, 2, 3"
                    + "), aggregated AS ("
                    + "  SELECT practice_slug, jsonb_agg(jsonb_build_object("
                    + "           'sourceKind', source_kind, 'reasonCode', reason_code, 'reviewsAffected', reviews"
                    + "         ) ORDER BY reviews DESC, source_kind NULLS FIRST, reason_code) AS blockers"
                    + "    FROM blocked GROUP BY 1"
                    + ")"
                    + " SELECT counted.practice_slug AS practiceSlug,"
                    + "        counted.considered AS consideredReviews,"
                    + "        counted.reviewed AS reviewedCount,"
                    + "        coalesce(aggregated.blockers, '[]'::jsonb)::text AS blockersObserved"
                    + "   FROM counted LEFT JOIN aggregated USING (practice_slug)"
                    + "  ORDER BY counted.practice_slug",
            nativeQuery = true)
    List<PracticeReadinessRow> findReadinessOutcomes(
            @Param("workspaceId") Long workspaceId, @Param("window") int window);

    interface PracticeReadinessRow {
        String getPracticeSlug();

        int getConsideredReviews();

        int getReviewedCount();

        /** A JSON array of blockers, aggregated by the query so one row is one practice. */
        String getBlockersObserved();
    }

    /**
     * One row of the workspace job listing: every column {@code AgentJobDTO} renders, and no other.
     * Each getter carries the nullness {@link AgentJob} declares for the same field, so a row and an
     * entity say the same thing about what may be absent.
     */
    interface AgentJobListRow extends ReviewRunTargetRow {
        AgentJobStatus getStatus();

        @Nullable
        JsonNode getOutput();

        JsonNode getConfigSnapshot();

        @Nullable
        Integer getExitCode();

        @Nullable
        String getErrorMessage();

        @Nullable
        DeliveryStatus getDeliveryStatus();

        @Nullable
        String getDeliveryCommentId();

        int getRetryCount();

        Instant getAvailableAt();

        @Nullable
        String getHoldReason();

        Instant getCreatedAt();

        @Nullable
        Instant getStartedAt();

        @Nullable
        Instant getCompletedAt();

        @Nullable
        String getLlmModel();

        @Nullable
        String getLlmModelVersion();

        @Nullable
        Integer getLlmTotalCalls();

        @Nullable
        Integer getLlmTotalInputTokens();

        @Nullable
        Integer getLlmTotalOutputTokens();

        @Nullable
        Integer getLlmTotalReasoningTokens();

        @Nullable
        Integer getLlmCacheReadTokens();

        @Nullable
        Integer getLlmCacheWriteTokens();
    }

    /** What one delivery-recovery pass decides on before it reads a job whole. */
    interface StuckDeliveryRow {
        UUID getId();

        short getDeliveryAttempts();

        @Nullable
        String getDeliveryCommentId();
    }

    interface ReviewRunTargetRow {
        UUID getId();

        AgentJobType getJobType();

        @Nullable
        IntegrationKind getIntegrationKind();

        @Nullable
        JsonNode getMetadata();
    }

    interface ReviewRunSummaryRow extends ReviewRunTargetRow {
        AgentJobStatus getStatus();

        Instant getCreatedAt();
    }
}
