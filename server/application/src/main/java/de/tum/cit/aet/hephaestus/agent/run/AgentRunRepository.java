package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every write of a run is a conditional update on the database clock. A write of a holder names its epoch and changes
 * nothing under another one. {@code clock_timestamp()} rather than {@code now()}: a lease is compared with the time of
 * the statement, not with the start of a transaction that may have waited for a lock.
 */
interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    /** How the end of a claim settles a call it left open. */
    String SETTLE = """
            UPDATE llm_usage_event
               SET call_state = 'SETTLED', measurement_verification = 'UNVERIFIABLE', pricing_state = 'UNPRICED',
                   cost_usd = NULL, unreported_calls = 1
            """;

    /** A leased run as a claim or a lookup returns it. */
    interface LeaseRow {
        UUID getId();

        long getWorkspaceId();

        String getKind();

        String getConcurrencyKey();

        int getAttempt();

        int getLeaseEpoch();

        Instant getLeasedUntil();

        @Nullable
        Instant getDeadline();
    }

    /** A merged conversation keeps its ledger order; other subjects keep the order in which their runs were created. */
    String SUBJECT_PREDECESSOR = """
            COALESCE((SELECT earlier.seq < question.seq
                        FROM mentor_turn earlier_turn
                        JOIN mentor_entry earlier ON earlier.turn_id = earlier_turn.id
                            AND earlier.workspace_id = earlier_turn.workspace_id AND earlier.kind = 'USER'
                        JOIN mentor_turn current_turn ON current_turn.run_id = r.id
                            AND current_turn.workspace_id = r.workspace_id
                        JOIN mentor_entry question ON question.turn_id = current_turn.id
                            AND question.workspace_id = current_turn.workspace_id AND question.kind = 'USER'
                       WHERE earlier_turn.run_id = s.id AND earlier_turn.workspace_id = s.workspace_id),
                     (s.created_at, s.id) < (r.created_at, r.id))
            """;

    /**
     * The rule that orders the claim. A run is offered when it is due, its workspace is active, no run of its subject is
     * leased, and no older run of its subject waits. Then: band, the fewest leased runs of its workspace, age. A run
     * that has waited longer than {@code :agingSeconds} since it was due orders one band higher for each such wait, but
     * never into the interactive band, so no queued work waits without bound and no batch work overtakes a person.
     */
    String CLAIMABLE = """
            FROM agent_run r
            JOIN workspace w ON w.id = r.workspace_id AND w.status = 'ACTIVE'
            LEFT JOIN (SELECT workspace_id, count(*) AS leased FROM agent_run WHERE state = 'LEASED'
                       GROUP BY workspace_id) busy ON busy.workspace_id = r.workspace_id
            WHERE r.state = 'QUEUED' AND r.available_at <= clock_timestamp()
              AND NOT EXISTS (SELECT 1 FROM agent_run s
                              WHERE s.workspace_id = r.workspace_id AND s.concurrency_key = r.concurrency_key
                                AND (s.state = 'LEASED'
                                     OR (s.state = 'QUEUED' AND (""" + SUBJECT_PREDECESSOR + """
                                     ))))
            """;

    String ORDER = """
            ORDER BY CASE WHEN r.band = 'INTERACTIVE' THEN 0
                          ELSE GREATEST(1, (CASE r.band WHEN 'BATCH' THEN 1 ELSE 2 END)
                               - FLOOR(EXTRACT(EPOCH FROM (clock_timestamp() - r.available_at)) / :agingSeconds))
                     END,
                     COALESCE(busy.leased, 0), r.created_at, r.id
            """;

    String CLAIM_ORDER = ORDER + """
            LIMIT 1 FOR UPDATE OF r SKIP LOCKED
            """;

    /** The lease a claim or a renewal gives: a person waits on a turn, so it is taken back sooner. */
    String LEASE_SECONDS = "CASE r.kind WHEN 'TURN' THEN :turnTtlSeconds ELSE :ttlSeconds END";

    /** A queued run that the claim would offer, as the dispatch of sandboxes reads it. */
    interface DueRow {
        UUID getId();

        long getWorkspaceId();

        String getKind();

        String getConcurrencyKey();
    }

    /**
     * The queued runs of these kinds that the claim would offer next, in its order, without leasing them: the dispatch
     * starts their sandboxes, and each sandbox claims its own run.
     */
    @WorkspaceAgnostic("The dispatch reads the due runs of every workspace, in the claim's fair-share order")
    @Query(value = """
            SELECT r.id AS id, r.workspace_id AS "workspaceId", r.kind AS kind,
                   r.concurrency_key AS "concurrencyKey"
            """ + CLAIMABLE + """
                AND r.kind IN (:kinds)
            """ + ORDER + """
            LIMIT :limit
            """, nativeQuery = true)
    List<DueRow> findDue(
            @Param("kinds") Collection<String> kinds,
            @Param("limit") int limit,
            @Param("agingSeconds") long agingSeconds);

    String LEASE = """
            UPDATE agent_run r SET state = 'LEASED', lease_epoch = r.lease_epoch + 1, hold_reason = NULL,
                   leased_until = clock_timestamp() + make_interval(secs =>""" + LEASE_SECONDS + """
            ),
                   started_at = COALESCE(r.started_at, clock_timestamp())
              FROM picked WHERE r.id = picked.id
            RETURNING r.id AS id, r.workspace_id AS "workspaceId", r.kind AS kind,
                      r.concurrency_key AS "concurrencyKey", r.attempt AS attempt, r.lease_epoch AS "leaseEpoch",
                      r.leased_until AS "leasedUntil", r.deadline AS deadline
            """;

    @WorkspaceAgnostic("The claim offers the queued runs of every workspace, in fair-share order")
    @Query(value = "WITH picked AS (SELECT r.id " + CLAIMABLE + """
                AND r.kind IN (:kinds)
                AND (:#{#excluded.isEmpty()} OR r.id NOT IN (:excluded))
            """ + CLAIM_ORDER + ")" + LEASE, nativeQuery = true)
    Optional<LeaseRow> claim(
            @Param("kinds") Collection<String> kinds,
            @Param("excluded") Collection<UUID> excluded,
            @Param("ttlSeconds") long ttlSeconds,
            @Param("turnTtlSeconds") long turnTtlSeconds,
            @Param("agingSeconds") long agingSeconds);

    /** The claim of one subject, for the sandbox that serves only it: the same rule and order, within one subject. */
    @Query(value = "WITH picked AS (SELECT r.id " + CLAIMABLE + """
                AND r.workspace_id = :workspaceId AND r.concurrency_key = :concurrencyKey AND r.kind IN (:kinds)
            """ + CLAIM_ORDER + ")" + LEASE, nativeQuery = true)
    Optional<LeaseRow> claimSubject(
            @Param("workspaceId") long workspaceId,
            @Param("concurrencyKey") String concurrencyKey,
            @Param("kinds") Collection<String> kinds,
            @Param("ttlSeconds") long ttlSeconds,
            @Param("turnTtlSeconds") long turnTtlSeconds,
            @Param("agingSeconds") long agingSeconds);

    /** Serialises the capped claims of one kind until the transaction ends ({@link AgentRuns#claimCapped}). */
    @WorkspaceAgnostic("A kind's cap holds across every workspace")
    @Query(
            value =
                    "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended('agent-run-claim:' || :kind, 0))) held",
            nativeQuery = true)
    int lockKindClaims(@Param("kind") String kind);

    @WorkspaceAgnostic("A kind's cap counts its leased runs in every workspace")
    @Query(value = "SELECT count(*) FROM agent_run WHERE kind = :kind AND state = 'LEASED'", nativeQuery = true)
    int countLeased(@Param("kind") String kind);

    @Query(value = """
            UPDATE agent_run r SET leased_until = clock_timestamp() + make_interval(secs =>""" + LEASE_SECONDS + """
            )
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until > clock_timestamp() AND (deadline IS NULL OR deadline > clock_timestamp())
            RETURNING leased_until
            """, nativeQuery = true)
    Optional<Instant> renew(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("ttlSeconds") long ttlSeconds,
            @Param("turnTtlSeconds") long turnTtlSeconds);

    /** Moves an open run's deadline to {@code by} unless it is earlier already ({@link AgentRuns#endBy}). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET deadline = LEAST(COALESCE(deadline, :by), :by)
             WHERE id = :runId AND workspace_id = :workspaceId AND state <> 'ENDED'
            """, nativeQuery = true)
    int endBy(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("by") Instant by);

    /** {@link #endBy} for the claim that still holds the run ({@link AgentRuns#limit}). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET deadline = LEAST(COALESCE(deadline, :by), :by)
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
            """, nativeQuery = true)
    int limit(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("by") Instant by);

    /** Moves the queued runs of one kind from one subject to another ({@link AgentRuns#moveQueued}). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET concurrency_key = :to
             WHERE workspace_id = :workspaceId AND kind = :kind AND concurrency_key = :from AND state = 'QUEUED'
            """, nativeQuery = true)
    int moveQueued(
            @Param("workspaceId") long workspaceId,
            @Param("kind") String kind,
            @Param("from") String from,
            @Param("to") String to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'QUEUED', leased_until = NULL, deadline = NULL, attempt = attempt + :spent,
                   available_at = clock_timestamp() + make_interval(secs => :delaySeconds), hold_reason = :hold
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
            """, nativeQuery = true)
    int requeue(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("spent") int spent,
            @Param("delaySeconds") long delaySeconds,
            @Param("hold") @Nullable String hold);

    /**
     * A holder's end. It keeps the caller's persistence context: a holder ends its run with its domain row, and the
     * row's later writes in the same transaction must not land on a detached entity.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, stop_reason = :reason,
                   ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
            """, nativeQuery = true)
    int end(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, stop_reason = 'CANCELLED',
                   ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id = :workspaceId AND state <> 'ENDED'
            """, nativeQuery = true)
    int cancel(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    @WorkspaceAgnostic("A fleet-wide verdict ends the runs it selected in every workspace")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, stop_reason = 'CANCELLED',
                   ended_at = clock_timestamp()
             WHERE id IN (:runIds) AND state <> 'ENDED'
            """, nativeQuery = true)
    int cancelAll(@Param("runIds") Collection<UUID> runIds);

    /** Holds a queued run until {@code delaySeconds} from now; a leased or ended run has no hold. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET available_at = clock_timestamp() + make_interval(secs => :delaySeconds), hold_reason = :hold
             WHERE id = :runId AND workspace_id = :workspaceId AND state = 'QUEUED'
            """, nativeQuery = true)
    int hold(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("hold") String hold,
            @Param("delaySeconds") long delaySeconds);

    /** Offers now the workspace's queued runs that {@code hold} held; a retry's backoff is not a hold and stays. */
    // A list result runs as a query, which neither flushes nor clears; Hibernate flushes before a native statement.
    // So a run or job loaded earlier in the caller's transaction keeps its stale hold until it is read again.
    @Query(value = """
            UPDATE agent_run SET available_at = clock_timestamp(), hold_reason = NULL
             WHERE workspace_id = :workspaceId AND state = 'QUEUED' AND hold_reason = :hold
            RETURNING kind
            """, nativeQuery = true)
    List<String> releaseHolds(@Param("workspaceId") long workspaceId, @Param("hold") String hold);

    /**
     * Holds the lease against every concurrent requeue, end or reap until the caller's transaction ends: an update of
     * the row waits for this share lock.
     */
    @Query(value = """
            SELECT 1 FROM agent_run
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until > clock_timestamp()
               FOR SHARE
            """, nativeQuery = true)
    Optional<Integer> lockCurrent(
            @Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("epoch") int epoch);

    @Query(value = """
            SELECT 1 FROM agent_run
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until > clock_timestamp()
            """, nativeQuery = true)
    Optional<Integer> findCurrent(
            @Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("epoch") int epoch);

    /**
     * A run whose lease expired. Not locked: its kind's domain row is locked first, and the reaper's conditional write
     * then decides which replica takes it back.
     */
    interface ExpiredRow {
        UUID getId();

        long getWorkspaceId();

        String getKind();

        String getConcurrencyKey();

        int getAttempt();

        int getLeaseEpoch();

        boolean getPastDeadline();
    }

    @WorkspaceAgnostic("The reaper takes back expired leases in every workspace; each row carries its workspace")
    @Query(value = """
            SELECT id, workspace_id AS "workspaceId", kind, concurrency_key AS "concurrencyKey", attempt,
                   lease_epoch AS "leaseEpoch",
                   (deadline IS NOT NULL AND deadline <= clock_timestamp()) AS "pastDeadline"
              FROM agent_run WHERE kind = :kind AND state = 'LEASED'
               AND (leased_until < clock_timestamp() OR deadline <= clock_timestamp())
               AND (:#{#skipped.isEmpty()} OR id NOT IN (:skipped))
             ORDER BY LEAST(leased_until, COALESCE(deadline, leased_until)), id LIMIT 1
            """, nativeQuery = true)
    Optional<ExpiredRow> findExpired(@Param("kind") String kind, @Param("skipped") Collection<UUID> skipped);

    /**
     * A queued run that has been due longer than its start window while nothing of its subject was ahead of it: it
     * waited for room, not for its subject. A run that waits behind its subject's leased or older run keeps waiting.
     */
    String NOT_STARTED = """
            (r.state = 'QUEUED' AND r.start_within_seconds IS NOT NULL
               AND r.available_at + make_interval(secs => r.start_within_seconds) <= clock_timestamp()
               AND NOT EXISTS (SELECT 1 FROM agent_run s
                               WHERE s.workspace_id = r.workspace_id AND s.concurrency_key = r.concurrency_key
                                 AND (s.state = 'LEASED'
                                      OR (s.state = 'QUEUED' AND (""" + SUBJECT_PREDECESSOR + """
                                      )))))
            """;

    @WorkspaceAgnostic("The reaper ends queued runs past their start window in every workspace")
    @Query(value = """
            SELECT r.id, r.workspace_id AS "workspaceId", r.kind, r.concurrency_key AS "concurrencyKey", r.attempt,
                   r.lease_epoch AS "leaseEpoch",
                   false AS "pastDeadline"
              FROM agent_run r WHERE r.kind = :kind AND""" + NOT_STARTED + """
               AND (:#{#skipped.isEmpty()} OR r.id NOT IN (:skipped))
             ORDER BY r.available_at, r.id LIMIT 1
            """, nativeQuery = true)
    Optional<ExpiredRow> findOverdueQueued(@Param("kind") String kind, @Param("skipped") Collection<UUID> skipped);

    /** The reaper's write: the expired holder's epoch fences it, so a renewal that won the race keeps its lease. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'QUEUED', leased_until = NULL, deadline = NULL, attempt = attempt + 1,
                   available_at = clock_timestamp() + make_interval(secs => :delaySeconds)
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until < clock_timestamp()
            """, nativeQuery = true)
    int requeueExpired(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("delaySeconds") long delaySeconds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, stop_reason = :reason,
                   attempt = attempt + CASE WHEN state = 'LEASED' THEN 1 ELSE 0 END, ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch
               AND ((state = 'LEASED' AND (leased_until < clock_timestamp() OR deadline <= clock_timestamp()))
                    OR (state = 'QUEUED' AND id IN (SELECT r.id FROM agent_run r WHERE r.id = :runId AND""" + NOT_STARTED + """
               )))
            """, nativeQuery = true)
    int endExpired(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("reason") String reason);

    /**
     * Settles the model calls that the claim {@code epoch} left open: each ends unverifiable and unpriced, never at zero
     * cost. The caller writes the run's end or requeue in the same transaction. A call that returns later measures its
     * row again ({@code LlmUsageRecorder#complete}).
     *
     * @return how many calls were settled
     */
    @Modifying(flushAutomatically = true)
    @Query(value = SETTLE + """
             WHERE run_id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND call_state = 'OPEN'
            """, nativeQuery = true)
    int settleOpenCalls(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("epoch") int epoch);

    /** {@link #settleOpenCalls} for every claim of a run that someone other than its holder ends. */
    @Modifying(flushAutomatically = true)
    @Query(value = SETTLE + """
             WHERE run_id = :runId AND workspace_id = :workspaceId AND call_state = 'OPEN'
            """, nativeQuery = true)
    int settleOpenCallsOfRun(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    /** {@link #settleOpenCallsOfRun} for runs that a fleet-wide verdict ends in every workspace. */
    @WorkspaceAgnostic("A fleet-wide verdict ends the runs it selected in every workspace")
    @Modifying(flushAutomatically = true)
    @Query(value = SETTLE + """
             WHERE run_id IN (:runIds) AND call_state = 'OPEN'
            """, nativeQuery = true)
    int settleOpenCallsOfRuns(@Param("runIds") Collection<UUID> runIds);

    @WorkspaceAgnostic("The wake names only a run kind; every claimer reads the queue through the claim")
    @Query(value = "SELECT 1 FROM (SELECT pg_notify(:channel, :kind)) sent", nativeQuery = true)
    int notifyQueued(@Param("channel") String channel, @Param("kind") String kind);

    /** Whether the claim {@code epoch} ended the run itself; read from the table, never from a cached entity. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM agent_run WHERE id = :runId AND workspace_id = :workspaceId
                             AND state = 'ENDED' AND stop_reason = 'COMPLETED' AND lease_epoch = :epoch)
            """, nativeQuery = true)
    boolean endedBy(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("epoch") int epoch);

    @Query("SELECT r FROM AgentRun r WHERE r.id = :id AND r.workspaceId = :workspaceId")
    Optional<AgentRun> findByRef(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentRun r WHERE r.id = :id AND r.workspaceId = :workspaceId"
            + " AND r.state = de.tum.cit.aet.hephaestus.agent.run.AgentRunState.ENDED")
    int deleteEnded(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    @WorkspaceAgnostic("Retention deletes the ended runs of the domain rows it deleted, in every workspace")
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentRun r WHERE r.id IN :ids"
            + " AND r.state = de.tum.cit.aet.hephaestus.agent.run.AgentRunState.ENDED")
    int deleteAllEndedById(@Param("ids") Collection<UUID> ids);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentRun r WHERE r.workspaceId = :workspaceId AND r.id IN :ids")
    int deleteAllInWorkspace(@Param("workspaceId") long workspaceId, @Param("ids") Collection<UUID> ids);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentRun r WHERE r.workspaceId = :workspaceId")
    int deleteAllByWorkspaceId(@Param("workspaceId") long workspaceId);

    @WorkspaceAgnostic("Reads the database clock")
    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseClock();
}
