package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import de.tum.cit.aet.hephaestus.core.MaintenanceBarrier;
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
 * the statement, not with the start of a transaction that may have waited for a lock. Conversation work deadlines and
 * digest contact cutoffs can use scenario time during an evaluation.
 */
interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    /** How the end of a claim settles a call it left open. */
    String SETTLE = """
            UPDATE llm_usage_event
               SET call_state = 'SETTLED', measurement_verification = 'UNVERIFIABLE', pricing_state = 'UNPRICED',
                   cost_usd = NULL, unreported_calls = 1
            """;

    /** A leased run as a claim returns it. */
    interface LeaseRow {
        UUID getId();

        long getWorkspaceId();

        String getKind();

        String getBand();

        String getConcurrencyKey();

        int getAttempt();

        int getLeaseEpoch();

        Instant getLeasedAt();

        Instant getLeasedUntil();

        @Nullable
        Instant getDeadline();

        /** Seconds from the run's {@code available_at} to this claim, both on the database clock. */
        double getWaitedSeconds();
    }

    /** Share the stimulus join between ledger order and the digest cutoff. Inline it so lookups use the run key. */
    String TURN_STIMULI = """
            WITH turn_stimuli AS NOT MATERIALIZED (
                SELECT t.run_id, t.workspace_id, e.id, e.seq, e.kind, e.payload, e.redacted_at
                  FROM conversation_turn t
                  JOIN conversation_entry e ON e.turn_id = t.id AND e.workspace_id = t.workspace_id
                 WHERE e.kind IN ('USER', 'DIGEST')
            )
            """;

    /** Questions precede queued digests; each kind keeps conversation ledger order, and other subjects keep age order. */
    String SUBJECT_PREDECESSOR = """
            CASE WHEN r.kind='TURN' AND s.kind='DIGEST' THEN FALSE
                 WHEN r.kind='DIGEST' AND s.kind='TURN' THEN TRUE
                 ELSE COALESCE((SELECT earlier.seq < question.seq
                        FROM turn_stimuli earlier
                        JOIN turn_stimuli question ON question.run_id = r.id AND question.workspace_id = r.workspace_id
                       WHERE earlier.run_id = s.id AND earlier.workspace_id = s.workspace_id),
                     (s.created_at, s.id) < (r.created_at, r.id)) END
            """;

    /** No run of this subject is leased or queued ahead of this one. */
    String FIRST_OF_SUBJECT = """
            NOT EXISTS (SELECT 1 FROM agent_run s
                        WHERE s.workspace_id IS NOT DISTINCT FROM r.workspace_id AND s.concurrency_key = r.concurrency_key
                          AND (s.state = 'LEASED' OR (s.state = 'QUEUED' AND (""" + SUBJECT_PREDECESSOR + """
                          ))))
            """;

    String CONVERSATION_CLOCK = "COALESCE(CAST(:#{#time.replayNow()} AS timestamptz), clock_timestamp())";

    /** Legacy digests use the same Java calendar rules as new writes. A cutoff always reaches SQL as a UTC instant. */
    String DIGEST_WINDOW = """
            SELECT CASE WHEN resolved.cutoff IS NULL THEN 'UNKNOWN'
                        WHEN resolved.cutoff <=\s""" + CONVERSATION_CLOCK + """
                        THEN 'ELAPSED' ELSE 'OPEN' END
              FROM turn_stimuli e
              CROSS JOIN LATERAL (
                  SELECT CASE WHEN e.redacted_at IS NOT NULL THEN NULL
                              WHEN e.payload ->> 'startBy' IS NULL THEN CAST(
                                  CAST(:#{#time.legacyDigestCutoffs()} AS jsonb) ->> CAST(e.id AS text) AS timestamptz)
                              WHEN e.payload ->> 'startBy' ~ '^[0-9T:.+-]+Z$' THEN
                                  CASE WHEN pg_input_is_valid(e.payload ->> 'startBy', 'timestamp with time zone')
                                       THEN CAST(e.payload ->> 'startBy' AS timestamptz) END
                         END AS cutoff
              ) resolved
             WHERE e.run_id = r.id AND e.workspace_id = r.workspace_id AND e.kind = 'DIGEST'
            """;

    /** An unresolved window is neither claimable nor overdue. A concurrent old write can be read on the next pass. */
    String START_WINDOW = """
            COALESCE(CASE WHEN r.kind = 'DIGEST' THEN (
            """ + DIGEST_WINDOW + """
                ) END,
                CASE WHEN (CASE WHEN r.kind = 'DIGEST' THEN r.created_at ELSE r.available_at END)
                    + make_interval(secs => r.start_within_seconds) <= clock_timestamp()
                THEN 'ELAPSED' ELSE 'OPEN' END)
            """;

    String PAST_DEADLINE = "(r.deadline IS NOT NULL AND r.deadline <= CASE WHEN r.kind IN ('TURN','DIGEST') THEN "
            + CONVERSATION_CLOCK + " ELSE clock_timestamp() END)";

    String WITHIN_START_LIMITS = "  AND NOT " + PAST_DEADLINE + "\n  AND " + START_WINDOW + " = 'OPEN'\n";

    String READY_SUBJECT = """
            FROM agent_run r
            LEFT JOIN workspace w ON w.id = r.workspace_id AND w.status = 'ACTIVE'
            LEFT JOIN (SELECT workspace_id, count(*) AS leased FROM agent_run WHERE state = 'LEASED'
                       GROUP BY workspace_id) busy ON busy.workspace_id IS NOT DISTINCT FROM r.workspace_id
            WHERE (r.workspace_id IS NULL OR w.id IS NOT NULL)
              AND r.state = 'QUEUED' AND r.available_at <= clock_timestamp()
              AND\s""" + FIRST_OF_SUBJECT;

    /** A due run that is first of its subject remains eligible only until its deadline or start window elapses. */
    String CLAIMABLE = READY_SUBJECT + WITHIN_START_LIMITS;

    /** A deferred run waits for its {@code deferred_until}; the run gauges still count it as due. */
    String NOT_DEFERRED = """
              AND (r.deferred_until IS NULL OR r.deferred_until <= clock_timestamp())
            """;

    /**
     * No run is offered while a database migration runs ({@link MaintenanceBarrier}): no sandbox starts for it, and no
     * waiting {@code next} wakes for it. The run gauges still count it as due.
     */
    String BARRIER_LIFTED = "  AND " + MaintenanceBarrier.LIFTED + "\n";

    /** A draft returns its report-specific sandbox while it waits. Its next attempt joins the other due drafts. */
    String DUE_SINCE = "GREATEST(r.available_at, CASE WHEN r.kind = 'DRAFT_REPORT' THEN r.deferred_until END)";

    /**
     * Order by band, then the fewest leased runs of a workspace, then age. Every {@code :agingSeconds} since the
     * run was due, it moves up one band, up to the batch band.
     */
    String ORDER = """
            ORDER BY CASE WHEN r.band = 'INTERACTIVE' THEN 0
                          ELSE GREATEST(1, (CASE r.band WHEN 'BATCH' THEN 1 ELSE 2 END)
                               - FLOOR(EXTRACT(EPOCH FROM (clock_timestamp() -\s""" + DUE_SINCE + """
                               )) / :agingSeconds))
                     END,
                     COALESCE(busy.leased, 0),
                     CASE WHEN r.kind = 'DRAFT_REPORT' THEN (""" + DUE_SINCE + """
                     ) ELSE r.created_at END, r.id
            """;

    String PICK_ONE = """
            LIMIT 1 FOR UPDATE OF r SKIP LOCKED
            """;

    String CLAIM_ORDER = ORDER + PICK_ONE;

    /** The lease a claim or a renewal gives: a person waits on a turn, so it is taken back sooner. */
    String LEASE_SECONDS = "CASE WHEN r.kind IN ('TURN','DIGEST') THEN :turnTtlSeconds ELSE :ttlSeconds END";

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
    @Query(
            value = TURN_STIMULI + """
            SELECT r.id AS id, COALESCE(r.workspace_id, 0) AS "workspaceId", r.kind AS kind,
                   r.concurrency_key AS "concurrencyKey"
            """ + CLAIMABLE + NOT_DEFERRED + BARRIER_LIFTED + """
                AND r.kind IN (:kinds)
            """ + ORDER + """
            LIMIT :limit OFFSET :offset
            """,
            nativeQuery = true)
    List<DueRow> findDue(
            @Param("kinds") Collection<String> kinds,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("agingSeconds") long agingSeconds,
            @Param("time") AgentRunTiming.Reading time);

    /**
     * The queued runs of these kinds and subjects that a claim would offer now, in no order: a replica wakes the
     * {@code next} requests of these subjects that wait.
     */
    @WorkspaceAgnostic(
            "A replica holds the waiting requests of every workspace; each woken request claims its own subject")
    @Query(value = TURN_STIMULI + """
            SELECT r.id AS id, COALESCE(r.workspace_id, 0) AS "workspaceId", r.kind AS kind,
                   r.concurrency_key AS "concurrencyKey"
            """ + CLAIMABLE + NOT_DEFERRED + BARRIER_LIFTED + """
                AND r.kind IN (:kinds) AND r.concurrency_key IN (:subjectKeys)
                AND (r.workspace_id IN (:workspaceIds) OR (r.workspace_id IS NULL AND 0 IN (:workspaceIds)))
            """, nativeQuery = true)
    List<DueRow> findDueOfSubjects(
            @Param("kinds") Collection<String> kinds,
            @Param("workspaceIds") Collection<Long> workspaceIds,
            @Param("subjectKeys") Collection<String> subjectKeys,
            @Param("time") AgentRunTiming.Reading time);

    // The start limit can pass while the claim orders its candidates and locks the picked row.
    String LEASE = """
            , lease_time AS MATERIALIZED (SELECT id, clock_timestamp() AS leased_at FROM picked)
            UPDATE agent_run r SET state = 'LEASED', lease_epoch = r.lease_epoch + 1, hold_reason = NULL, deferred_until = NULL,
                   leased_until = lease_time.leased_at + make_interval(secs =>""" + LEASE_SECONDS + """
            ),
                   started_at = COALESCE(r.started_at, lease_time.leased_at)
              FROM lease_time WHERE r.id = lease_time.id
            """ + WITHIN_START_LIMITS + """
            RETURNING r.id AS id, COALESCE(r.workspace_id, 0) AS "workspaceId", r.kind AS kind, r.band AS band,
                      r.concurrency_key AS "concurrencyKey", r.attempt AS attempt, r.lease_epoch AS "leaseEpoch",
                      lease_time.leased_at AS "leasedAt", r.leased_until AS "leasedUntil", r.deadline AS deadline,
                      EXTRACT(EPOCH FROM lease_time.leased_at - r.available_at)::double precision AS "waitedSeconds"
            """;

    @WorkspaceAgnostic("The claim offers the queued runs of every workspace, in fair-share order")
    @Query(
            value = TURN_STIMULI + ", picked AS (SELECT r.id " + CLAIMABLE + NOT_DEFERRED + BARRIER_LIFTED + """
                AND r.kind IN (:kinds)
                AND (:#{#excluded.isEmpty()} OR r.id NOT IN (:excluded))
            """
                    + CLAIM_ORDER + ")"
                    + LEASE,
            nativeQuery = true)
    Optional<LeaseRow> claim(
            @Param("kinds") Collection<String> kinds,
            @Param("excluded") Collection<UUID> excluded,
            @Param("ttlSeconds") long ttlSeconds,
            @Param("turnTtlSeconds") long turnTtlSeconds,
            @Param("agingSeconds") long agingSeconds,
            @Param("time") AgentRunTiming.Reading time);

    /** Only the first run of this subject can be eligible, so there is no fleet order to apply. */
    @Query(
            value = TURN_STIMULI + ", picked AS (SELECT r.id " + CLAIMABLE + NOT_DEFERRED + BARRIER_LIFTED + """
                AND r.workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND r.concurrency_key = :concurrencyKey AND r.kind IN (:kinds)
            """
                    + PICK_ONE + ")"
                    + LEASE,
            nativeQuery = true)
    Optional<LeaseRow> claimSubject(
            @Param("workspaceId") long workspaceId,
            @Param("concurrencyKey") String concurrencyKey,
            @Param("kinds") Collection<String> kinds,
            @Param("ttlSeconds") long ttlSeconds,
            @Param("turnTtlSeconds") long turnTtlSeconds,
            @Param("time") AgentRunTiming.Reading time);

    String KIND_CLAIM_LOCK = "hashtextextended('agent-run-claim:' || :kind, 0)";

    /** Serialises the capped claims of one kind until the transaction ends ({@link AgentRuns#claimCapped}). */
    @WorkspaceAgnostic("A kind's cap holds across every workspace")
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(" + KIND_CLAIM_LOCK + ")) held", nativeQuery = true)
    int lockKindClaims(@Param("kind") String kind);

    /** A subject pull skips a busy cap lock instead of waiting while it holds its sandbox row. */
    @WorkspaceAgnostic("A kind's cap holds across every workspace")
    @Query(value = "SELECT pg_try_advisory_xact_lock(" + KIND_CLAIM_LOCK + ")", nativeQuery = true)
    boolean tryLockKindClaims(@Param("kind") String kind);

    @WorkspaceAgnostic("A kind's cap counts its leased runs in every workspace")
    @Query(value = "SELECT count(*) FROM agent_run WHERE kind = :kind AND state = 'LEASED'", nativeQuery = true)
    int countLeased(@Param("kind") String kind);

    /** The runs of one kind, band, state and hold that have not ended, as the sampler reads them. */
    interface OpenRow {
        String getKind();

        String getBand();

        String getState();

        @Nullable
        String getHoldReason();

        long getRuns();

        /** Of these runs, the ones with no hold that {@link AgentRunRepository#CLAIMABLE} offers: a claim could take them now. */
        long getDue();

        /** Seconds since the oldest due one became due; 0 when none is due. */
        long getOldestDueSeconds();
    }

    /**
     * One pass over the runs that have not ended, for the run gauges ({@link AgentRunSampler}). The counts take every
     * open run. A run is due when {@link AgentRunRepository#CLAIMABLE} offers it and it has no hold, so a run that no claim may take, such
     * as one of a suspended workspace, never reads as a stalled queue.
     */
    @WorkspaceAgnostic("The runs of the whole instance; the gauges name no workspace")
    @Query(value = TURN_STIMULI + """
            SELECT o.kind AS "kind", o.band AS "band", o.state AS "state", o.hold_reason AS "holdReason",
                   count(*) AS "runs",
                   count(due.id) FILTER (WHERE o.hold_reason IS NULL) AS "due",
                   COALESCE(floor(EXTRACT(EPOCH FROM clock_timestamp() - min(o.available_at)
                       FILTER (WHERE due.id IS NOT NULL AND o.hold_reason IS NULL))), 0)::bigint
                       AS "oldestDueSeconds"
              FROM agent_run o
              LEFT JOIN (SELECT r.id AS id
            """ + CLAIMABLE + """
                        ) due ON due.id = o.id
             WHERE o.state <> 'ENDED'
             GROUP BY o.kind, o.band, o.state, o.hold_reason
            """, nativeQuery = true)
    List<OpenRow> open(@Param("time") AgentRunTiming.Reading time);

    @Query(value = """
            UPDATE agent_run r SET leased_until = clock_timestamp() + make_interval(secs =>""" + LEASE_SECONDS + """
            )
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until > clock_timestamp() AND NOT\s""" + PAST_DEADLINE + """
            RETURNING leased_until
            """, nativeQuery = true)
    Optional<Instant> renew(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("ttlSeconds") long ttlSeconds,
            @Param("turnTtlSeconds") long turnTtlSeconds,
            @Param("time") AgentRunTiming.Reading time);

    /** Moves an open run's deadline to {@code by} unless it is earlier already ({@link AgentRuns#endBy}). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET deadline = LEAST(COALESCE(deadline, :by), :by)
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND state <> 'ENDED'
            """, nativeQuery = true)
    int endBy(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("by") Instant by);

    /** {@link #endBy} for the claim that still holds the run ({@link AgentRuns#limit}). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET deadline = LEAST(COALESCE(deadline, :by), :by)
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
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
             WHERE workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND kind = :kind AND concurrency_key = :from AND state = 'QUEUED'
            """, nativeQuery = true)
    int moveQueued(
            @Param("workspaceId") long workspaceId,
            @Param("kind") String kind,
            @Param("from") String from,
            @Param("to") String to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'QUEUED', leased_until = NULL,
                   deadline = CASE WHEN kind IN ('REVIEW_COMPOSE', 'DRAFT_REPORT') THEN deadline ELSE NULL END, attempt = attempt + :spent,
                   available_at = clock_timestamp() + make_interval(secs => :delaySeconds), hold_reason = :hold
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
            """, nativeQuery = true)
    int requeue(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("spent") int spent,
            @Param("delaySeconds") long delaySeconds,
            @Param("hold") @Nullable String hold);

    /** A deferred run, as {@link #defer} left it: its kind, its subject and how long it has been due. */
    interface DeferredRow {
        String getKind();

        String getConcurrencyKey();

        double getDueSeconds();
    }

    /**
     * The holder's requeue of a run that it could not start ({@link AgentRuns#defer}): {@code available_at} stays, and
     * no claim takes the run for {@code :waitSeconds}.
     */
    @Query(value = """
            UPDATE agent_run SET state = 'QUEUED', leased_until = NULL,
                   deadline = CASE WHEN kind IN ('REVIEW_COMPOSE', 'DRAFT_REPORT') THEN deadline ELSE NULL END,
                   deferred_until = clock_timestamp() + make_interval(secs => :waitSeconds)
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
            RETURNING kind AS kind, concurrency_key AS "concurrencyKey",
                      EXTRACT(EPOCH FROM clock_timestamp() - available_at)::double precision AS "dueSeconds"
            """, nativeQuery = true)
    Optional<DeferredRow> defer(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("waitSeconds") long waitSeconds);

    /**
     * A holder's end. It keeps the caller's persistence context: a holder ends its run with its domain row, and the
     * row's later writes in the same transaction must not land on a detached entity.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, stop_reason = :reason,
                   ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
            """, nativeQuery = true)
    int end(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, deferred_until = NULL, stop_reason = 'CANCELLED',
                   ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND state <> 'ENDED'
            """, nativeQuery = true)
    int cancel(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    @WorkspaceAgnostic("A fleet-wide verdict ends the runs it selected in every workspace")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, deferred_until = NULL, stop_reason = 'CANCELLED',
                   ended_at = clock_timestamp()
             WHERE id IN (:runIds) AND state <> 'ENDED'
            """, nativeQuery = true)
    int cancelAll(@Param("runIds") Collection<UUID> runIds);

    /** Holds a queued run until {@code delaySeconds} from now; a leased or ended run has no hold. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET available_at = clock_timestamp() + make_interval(secs => :delaySeconds), hold_reason = :hold
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND state = 'QUEUED'
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
             WHERE workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND state = 'QUEUED' AND hold_reason = :hold
            RETURNING kind
            """, nativeQuery = true)
    List<String> releaseHolds(@Param("workspaceId") long workspaceId, @Param("hold") String hold);

    /**
     * Holds the lease against every concurrent requeue, end or reap until the caller's transaction ends: an update of
     * the row waits for this share lock.
     */
    @Query(value = """
            SELECT 1 FROM agent_run
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until > clock_timestamp()
               FOR SHARE
            """, nativeQuery = true)
    Optional<Integer> lockCurrent(
            @Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("epoch") int epoch);

    interface ConversationHandoffRow {
        Instant getLeasedUntil();

        @Nullable
        Instant getDeadline();
    }

    /** Locks the run after its turn. The caller reads the clocks after any wait for this lock. */
    @Query(value = """
            SELECT leased_until AS "leasedUntil", deadline FROM agent_run
            WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch
              AND state = 'LEASED' AND kind IN ('TURN', 'DIGEST')
            FOR NO KEY UPDATE
            """, nativeQuery = true)
    Optional<ConversationHandoffRow> lockConversationHandoff(
            @Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("epoch") int epoch);

    @Query(value = """
            SELECT 1 FROM agent_run
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
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
            SELECT id, COALESCE(workspace_id, 0) AS "workspaceId", kind, concurrency_key AS "concurrencyKey", attempt,
                   lease_epoch AS "leaseEpoch",
            """ + PAST_DEADLINE + """
                   AS "pastDeadline"
              FROM agent_run r WHERE kind = :kind AND state = 'LEASED'
               AND (leased_until < clock_timestamp() OR\s""" + PAST_DEADLINE + """
               )
               AND (:#{#skipped.isEmpty()} OR id NOT IN (:skipped))
             ORDER BY LEAST(leased_until, COALESCE(deadline, leased_until)), id LIMIT 1
            """, nativeQuery = true)
    Optional<ExpiredRow> findExpired(
            @Param("kind") String kind,
            @Param("skipped") Collection<UUID> skipped,
            @Param("time") AgentRunTiming.Reading time);

    /** A digest's contact window applies even while another run of its subject is ahead of it. */
    String OVERDUE_QUEUED = "r.state = 'QUEUED' AND (" + PAST_DEADLINE + " OR (" + START_WINDOW + " = 'ELAPSED' AND "
            + "(r.kind = 'DIGEST' OR " + FIRST_OF_SUBJECT + ")))";

    @WorkspaceAgnostic("The reaper ends queued runs past their deadline or start window in every workspace")
    @Query(value = TURN_STIMULI + """
            SELECT r.id, COALESCE(r.workspace_id, 0) AS "workspaceId", r.kind, r.concurrency_key AS "concurrencyKey", r.attempt,
                   r.lease_epoch AS "leaseEpoch",
            """ + PAST_DEADLINE + """
                   AS "pastDeadline"
              FROM agent_run r WHERE r.kind = :kind AND (""" + OVERDUE_QUEUED + """
               )
               AND (:#{#skipped.isEmpty()} OR r.id NOT IN (:skipped))
             ORDER BY r.available_at, r.id LIMIT 1
            """, nativeQuery = true)
    Optional<ExpiredRow> findOverdueQueued(
            @Param("kind") String kind,
            @Param("skipped") Collection<UUID> skipped,
            @Param("time") AgentRunTiming.Reading time);

    /** The reaper's write: the expired holder's epoch fences it, so a renewal that won the race keeps its lease. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run r SET state = 'QUEUED', leased_until = NULL,
                   deadline = CASE WHEN kind = 'DRAFT_REPORT' THEN deadline END, attempt = attempt + 1,
                   available_at = clock_timestamp() + make_interval(secs => :delaySeconds)
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until < clock_timestamp() AND NOT\s""" + PAST_DEADLINE, nativeQuery = true)
    int requeueExpired(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("delaySeconds") long delaySeconds,
            @Param("time") AgentRunTiming.Reading time);

    /** Ends only the still-expired lease, spending its attempt once before its successor is inserted. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run r SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, stop_reason = 'CANCELLED',
                   attempt = attempt + 1, ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until < clock_timestamp() AND NOT\s""" + PAST_DEADLINE, nativeQuery = true)
    int endExpiredForContinuation(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("time") AgentRunTiming.Reading time);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = TURN_STIMULI + """
            UPDATE agent_run r SET state = 'ENDED', leased_until = NULL, hold_reason = NULL, deferred_until = NULL, stop_reason = :reason,
                   attempt = attempt + CASE WHEN state = 'LEASED' THEN 1 ELSE 0 END, ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch
               AND ((state = 'LEASED' AND (leased_until < clock_timestamp() OR\s""" + PAST_DEADLINE + """
                    ))
                    OR (state = 'QUEUED' AND id IN (SELECT r.id FROM agent_run r WHERE r.id = :runId AND (""" + OVERDUE_QUEUED + """
               ))))
            """, nativeQuery = true)
    int endExpired(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("reason") String reason,
            @Param("time") AgentRunTiming.Reading time);

    /**
     * Settles the model calls that the claim {@code epoch} left open: each ends unverifiable and unpriced, never at zero
     * cost. The caller writes the run's end or requeue in the same transaction. A call that returns later measures its
     * row again ({@code LlmUsageRecorder#complete}).
     *
     * @return how many calls were settled
     */
    @Modifying(flushAutomatically = true)
    @Query(value = SETTLE + """
             WHERE run_id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND lease_epoch = :epoch AND call_state = 'OPEN'
            """, nativeQuery = true)
    int settleOpenCalls(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId, @Param("epoch") int epoch);

    /** {@link #settleOpenCalls} for every claim of a run that someone other than its holder ends. */
    @Modifying(flushAutomatically = true)
    @Query(value = SETTLE + """
             WHERE run_id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0) AND call_state = 'OPEN'
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

    /** Whether the claim already ended with this outcome; read from the table, never from a cached entity. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM agent_run WHERE id = :runId AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0)
                             AND state = 'ENDED' AND stop_reason = :reason AND lease_epoch = :epoch)
            """, nativeQuery = true)
    boolean endedBy(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("reason") String reason);

    @Query(
            "SELECT r FROM AgentRun r WHERE r.id = :id AND (r.workspaceId = :workspaceId OR (r.workspaceId IS NULL AND :workspaceId = 0))")
    Optional<AgentRun> findByRef(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /** Whether the run waits on a hold, read from the row: a native requeue in this transaction is not in the cache. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM agent_run WHERE id = :id AND workspace_id IS NOT DISTINCT FROM NULLIF(:workspaceId, 0)
                             AND state = 'QUEUED' AND hold_reason IS NOT NULL)
            """, nativeQuery = true)
    boolean isHeld(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    @Modifying(flushAutomatically = true)
    @Query(
            "DELETE FROM AgentRun r WHERE r.id = :id AND (r.workspaceId = :workspaceId OR (r.workspaceId IS NULL AND :workspaceId = 0))"
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

    @Query(value = """
        SELECT CAST(id AS text) AS id, state, CASE WHEN state = 'QUEUED'
            AND GREATEST(available_at, deferred_until) > transaction_timestamp()
            AND NOT\s""" + PAST_DEADLINE + """
            THEN GREATEST(available_at, deferred_until) END AS "futureDueAt"
          FROM agent_run r WHERE state IN ('QUEUED', 'LEASED')
           AND workspace_id IN (:workspaceIds)
        """, nativeQuery = true)
    List<EvaluationWork.Row> findEvaluationWork(
            @Param("workspaceIds") List<Long> workspaceIds, @Param("time") AgentRunTiming.Reading time);
}
