package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
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
                                     OR (s.state = 'QUEUED' AND (s.created_at, s.id) < (r.created_at, r.id))))
            """;

    String CLAIM_ORDER = """
            ORDER BY CASE WHEN r.band = 'INTERACTIVE' THEN 0
                          ELSE GREATEST(1, (CASE r.band WHEN 'BATCH' THEN 1 ELSE 2 END)
                               - FLOOR(EXTRACT(EPOCH FROM (clock_timestamp() - r.available_at)) / :agingSeconds))
                     END,
                     COALESCE(busy.leased, 0), r.created_at, r.id
            LIMIT 1 FOR UPDATE OF r SKIP LOCKED
            """;

    String LEASE = """
            UPDATE agent_run r SET state = 'LEASED', lease_epoch = r.lease_epoch + 1,
                   leased_until = clock_timestamp() + make_interval(secs => :ttlSeconds),
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
            @Param("agingSeconds") long agingSeconds);

    @Query(value = """
            UPDATE agent_run SET leased_until = clock_timestamp() + make_interval(secs => :ttlSeconds)
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
               AND leased_until > clock_timestamp()
            RETURNING leased_until
            """, nativeQuery = true)
    Optional<Instant> renew(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("ttlSeconds") long ttlSeconds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'QUEUED', leased_until = NULL, attempt = attempt + :spent,
                   available_at = clock_timestamp() + make_interval(secs => :delaySeconds)
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch AND state = 'LEASED'
            """, nativeQuery = true)
    int requeue(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("spent") int spent,
            @Param("delaySeconds") long delaySeconds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, stop_reason = :reason,
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
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, stop_reason = 'CANCELLED',
                   ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id = :workspaceId AND state <> 'ENDED'
            """, nativeQuery = true)
    int cancel(@Param("runId") UUID runId, @Param("workspaceId") long workspaceId);

    @WorkspaceAgnostic("A fleet-wide verdict ends the runs it selected in every workspace")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, stop_reason = 'CANCELLED',
                   ended_at = clock_timestamp()
             WHERE id IN (:runIds) AND state <> 'ENDED'
            """, nativeQuery = true)
    int cancelAll(@Param("runIds") Collection<UUID> runIds);

    /** Moves a queued run's next offer; a leased or ended run keeps its own. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET available_at = clock_timestamp() + make_interval(secs => :delaySeconds)
             WHERE id = :runId AND workspace_id = :workspaceId AND state = 'QUEUED'
            """, nativeQuery = true)
    int defer(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("delaySeconds") long delaySeconds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET available_at = clock_timestamp()
             WHERE workspace_id = :workspaceId AND id IN (:runIds) AND state = 'QUEUED'
            """, nativeQuery = true)
    int releaseAll(@Param("workspaceId") long workspaceId, @Param("runIds") Collection<UUID> runIds);

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
              FROM agent_run WHERE kind = :kind AND state = 'LEASED' AND leased_until < clock_timestamp()
               AND (:#{#skipped.isEmpty()} OR id NOT IN (:skipped))
             ORDER BY leased_until, id LIMIT 1
            """, nativeQuery = true)
    Optional<ExpiredRow> findExpired(@Param("kind") String kind, @Param("skipped") Collection<UUID> skipped);

    @WorkspaceAgnostic("The reaper ends queued runs past their deadline in every workspace")
    @Query(value = """
            SELECT id, workspace_id AS "workspaceId", kind, concurrency_key AS "concurrencyKey", attempt,
                   lease_epoch AS "leaseEpoch",
                   true AS "pastDeadline"
              FROM agent_run WHERE kind = :kind AND state = 'QUEUED' AND deadline IS NOT NULL AND deadline <= clock_timestamp()
               AND (:#{#skipped.isEmpty()} OR id NOT IN (:skipped))
             ORDER BY deadline, id LIMIT 1
            """, nativeQuery = true)
    Optional<ExpiredRow> findOverdueQueued(@Param("kind") String kind, @Param("skipped") Collection<UUID> skipped);

    /** The reaper's write: the expired holder's epoch fences it, so a renewal that won the race keeps its lease. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_run SET state = 'QUEUED', leased_until = NULL, attempt = attempt + 1,
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
            UPDATE agent_run SET state = 'ENDED', leased_until = NULL, stop_reason = :reason,
                   attempt = attempt + CASE WHEN state = 'LEASED' THEN 1 ELSE 0 END, ended_at = clock_timestamp()
             WHERE id = :runId AND workspace_id = :workspaceId AND lease_epoch = :epoch
               AND ((state = 'LEASED' AND leased_until < clock_timestamp())
                    OR (state = 'QUEUED' AND deadline <= clock_timestamp()))
            """, nativeQuery = true)
    int endExpired(
            @Param("runId") UUID runId,
            @Param("workspaceId") long workspaceId,
            @Param("epoch") int epoch,
            @Param("reason") String reason);

    @WorkspaceAgnostic("The wake names only a run kind; every claimer reads the queue through the claim")
    @Query(value = "SELECT 1 FROM (SELECT pg_notify(:channel, :kind)) sent", nativeQuery = true)
    int notifyQueued(@Param("channel") String channel, @Param("kind") String kind);

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
