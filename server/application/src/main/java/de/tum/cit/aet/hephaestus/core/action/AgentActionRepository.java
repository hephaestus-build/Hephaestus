package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The compare-and-set statements of the outbox. Every time is the database clock, so replicas with drifting clocks
 * agree on a lease. Every write after a claim names the claim's epoch and changes nothing when another claim took the
 * row. The trigger {@code guard_agent_action} refuses what {@link ActionState} does not allow.
 */
interface AgentActionRepository extends Repository<AgentAction, UUID> {

    String CLAIMED = """
            SELECT a.id AS "id", a.workspace_id AS "workspaceId", a.kind AS "kind", a.idempotency_key AS "idempotencyKey",
            a.payload::text AS "payload", a.attempts AS "attempts", a.lease_epoch AS "epoch",
            a.write_started_at IS NOT NULL AS "writeStarted", a.readback_ref AS "readbackRef"
            """;

    String ENDED = """
            SELECT kind AS "kind", state AS "state", stop_reason AS "stopReason" FROM ended
            """;

    interface ClaimRow {
        UUID getId();

        @Nullable
        Long getWorkspaceId();

        String getKind();

        String getIdempotencyKey();

        String getPayload();

        int getAttempts();

        long getEpoch();

        boolean getWriteStarted();

        @Nullable
        String getReadbackRef();
    }

    interface SettledRow {
        String getKind();

        String getState();

        @Nullable
        String getStopReason();
    }

    @WorkspaceAgnostic("Inserts one action under its own workspace; the idempotency key is unique across the instance")
    @Query(value = """
            WITH inserted AS (
              INSERT INTO agent_action (id, workspace_id, kind, lane_key, idempotency_key, state, payload,
                subject_account_id, max_attempts, next_attempt_at, expires_at, created_at, updated_at)
              VALUES (:id, CAST(:workspaceId AS bigint), :kind, :laneKey, :key, 'ADMITTED', CAST(:payload AS jsonb),
                CAST(:subject AS bigint), :maxAttempts, clock_timestamp(), CAST(:expiresAt AS timestamptz),
                clock_timestamp(), clock_timestamp())
              ON CONFLICT (idempotency_key) DO NOTHING
              RETURNING id)
            SELECT id FROM inserted
            """, nativeQuery = true)
    Optional<UUID> insertAdmitted(
            @Param("id") UUID id,
            @Param("workspaceId") @Nullable Long workspaceId,
            @Param("kind") String kind,
            @Param("laneKey") String laneKey,
            @Param("key") String idempotencyKey,
            @Param("payload") String payload,
            @Param("subject") @Nullable Long subjectAccountId,
            @Param("maxAttempts") int maxAttempts,
            @Param("expiresAt") @Nullable Instant expiresAt);

    @WorkspaceAgnostic("The idempotency key is unique across the instance and names one action")
    @Query(value = "SELECT id FROM agent_action WHERE idempotency_key = :key", nativeQuery = true)
    Optional<UUID> findIdByIdempotencyKey(@Param("key") String idempotencyKey);

    @WorkspaceAgnostic("Inserts one action under its own workspace, already claimed by its caller")
    @Query(value = """
            WITH a AS (
              INSERT INTO agent_action (id, workspace_id, kind, lane_key, idempotency_key, state, payload,
                subject_account_id, attempts, max_attempts, next_attempt_at, expires_at, lease_epoch, leased_until,
                created_at, updated_at)
              VALUES (:id, CAST(:workspaceId AS bigint), :kind, :laneKey, :key, 'EXECUTING', CAST(:payload AS jsonb),
                CAST(:subject AS bigint), 1, :maxAttempts, clock_timestamp(), CAST(:expiresAt AS timestamptz), 1,
                clock_timestamp() + make_interval(secs => :lease), clock_timestamp(), clock_timestamp())
              ON CONFLICT (idempotency_key) DO NOTHING
              RETURNING *)
            """ + CLAIMED + " FROM a", nativeQuery = true)
    Optional<ClaimRow> insertExecuting(
            @Param("id") UUID id,
            @Param("workspaceId") @Nullable Long workspaceId,
            @Param("kind") String kind,
            @Param("laneKey") String laneKey,
            @Param("key") String idempotencyKey,
            @Param("payload") String payload,
            @Param("subject") @Nullable Long subjectAccountId,
            @Param("maxAttempts") int maxAttempts,
            @Param("expiresAt") @Nullable Instant expiresAt,
            @Param("lease") long leaseSeconds);

    /**
     * Takes up to {@code limit} due lane heads: an admitted action whose time has come, or one whose lease expired.
     * A lane runs one action at a time: an action waits while another of its lane executes, and an admitted one also
     * waits for an earlier one that has not started. A reclaim never waits, or a lane whose holder crashed would stall.
     * The unique index {@code ux_agent_action_lane_executing} refuses a second claim that another replica made at
     * the same moment. An at-most-once kind whose write may have started is never taken again; {@link #endLost}
     * ends it. An expired action is not taken; {@link #endExpired} ends it.
     */
    @WorkspaceAgnostic("One executor claims the due lane heads of every workspace and of the instance")
    @Query(value = """
            WITH due AS (
              SELECT d.id FROM agent_action d
               WHERE ((d.state = 'ADMITTED' AND d.next_attempt_at <= clock_timestamp()
                       AND (d.expires_at IS NULL OR d.expires_at > clock_timestamp()))
                   OR (d.state = 'EXECUTING' AND d.leased_until <= clock_timestamp()
                       AND d.attempts < d.max_attempts
                       AND (d.write_started_at IS NULL OR d.kind NOT IN (:atMostOnce))))
                 AND NOT EXISTS (
                   SELECT 1 FROM agent_action b
                    WHERE b.lane_key = d.lane_key AND b.id <> d.id
                      AND (b.state = 'EXECUTING'
                        OR (d.state = 'ADMITTED' AND b.id < d.id AND b.state IN ('PROPOSED', 'ADMITTED'))))
               ORDER BY d.next_attempt_at, d.id
               LIMIT :limit
               FOR UPDATE OF d SKIP LOCKED),
            a AS (
              UPDATE agent_action c
                 SET state = 'EXECUTING', lease_epoch = c.lease_epoch + 1, attempts = c.attempts + 1,
                     leased_until = clock_timestamp() + make_interval(secs => :lease), updated_at = clock_timestamp()
                FROM due WHERE c.id = due.id
              RETURNING c.*)
            """ + CLAIMED + " FROM a", nativeQuery = true)
    List<ClaimRow> claim(
            @Param("limit") int limit,
            @Param("lease") long leaseSeconds,
            @Param("atMostOnce") Collection<String> atMostOnceKinds);

    /**
     * Records that the provider call starts now. Refused when another claim took the action, when an erasure deleted
     * it, or when less than {@code margin} of the lease is left: a reclaim must never overlap a call.
     */
    @WorkspaceAgnostic("Fences one claimed action by its id and epoch")
    @Modifying
    @Query(value = """
            UPDATE agent_action SET write_started_at = COALESCE(write_started_at, clock_timestamp()),
                   updated_at = clock_timestamp()
             WHERE id = :id AND lease_epoch = :epoch AND state = 'EXECUTING'
               AND leased_until > clock_timestamp() + make_interval(secs => :margin)
            """, nativeQuery = true)
    int beginWrite(@Param("id") UUID id, @Param("epoch") long epoch, @Param("margin") long marginSeconds);

    /** Ends the attempt's action. The epoch alone fences it: a late but confirmed result is still the truth. */
    @WorkspaceAgnostic("Settles one claimed action by its id and epoch")
    @Query(value = """
            WITH ended AS (
              UPDATE agent_action SET state = :state, stop_reason = :reason, readback_ref = COALESCE(CAST(:ref AS varchar), readback_ref),
                     ended_at = clock_timestamp(), payload = NULL, leased_until = NULL, updated_at = clock_timestamp()
               WHERE id = :id AND lease_epoch = :epoch AND state = 'EXECUTING'
              RETURNING kind, state, stop_reason)
            """ + ENDED, nativeQuery = true)
    Optional<SettledRow> end(
            @Param("id") UUID id,
            @Param("epoch") long epoch,
            @Param("state") String state,
            @Param("reason") String reason,
            @Param("ref") @Nullable String readbackRef);

    /**
     * Admits the action again after a proven retry, or ends it at its last attempt. A retry clears the write fence:
     * the provider proved it holds nothing.
     */
    @WorkspaceAgnostic("Settles one claimed action by its id and epoch")
    @Query(value = """
            WITH ended AS (
              UPDATE agent_action
                 SET state = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'ADMITTED' END,
                     stop_reason = CASE WHEN attempts >= max_attempts THEN 'ATTEMPTS_EXHAUSTED' END,
                     ended_at = CASE WHEN attempts >= max_attempts THEN clock_timestamp() END,
                     payload = CASE WHEN attempts >= max_attempts THEN NULL ELSE payload END,
                     write_started_at = CASE WHEN attempts >= max_attempts THEN write_started_at END,
                     next_attempt_at = clock_timestamp() + make_interval(secs => :after),
                     leased_until = NULL, updated_at = clock_timestamp()
               WHERE id = :id AND lease_epoch = :epoch AND state = 'EXECUTING'
              RETURNING kind, state, stop_reason)
            """ + ENDED, nativeQuery = true)
    Optional<SettledRow> retry(@Param("id") UUID id, @Param("epoch") long epoch, @Param("after") long afterSeconds);

    /** Admits the action again without spending the attempt: nothing was written. */
    @WorkspaceAgnostic("Settles one claimed action by its id and epoch")
    @Query(value = """
            WITH ended AS (
              UPDATE agent_action
                 SET state = 'ADMITTED', attempts = attempts - 1, write_started_at = NULL, leased_until = NULL,
                     next_attempt_at = clock_timestamp() + make_interval(secs => :after), updated_at = clock_timestamp()
               WHERE id = :id AND lease_epoch = :epoch AND state = 'EXECUTING'
              RETURNING kind, state, stop_reason)
            """ + ENDED, nativeQuery = true)
    Optional<SettledRow> defer(@Param("id") UUID id, @Param("epoch") long epoch, @Param("after") long afterSeconds);

    /** Cancels what expired before it started. */
    @WorkspaceAgnostic("The reaper ends expired actions of every workspace")
    @Query(value = """
            WITH ended AS (
              UPDATE agent_action SET state = 'CANCELLED', stop_reason = 'EXPIRED', payload = NULL,
                     ended_at = clock_timestamp(), updated_at = clock_timestamp()
               WHERE state IN ('PROPOSED', 'ADMITTED') AND expires_at <= clock_timestamp()
              RETURNING kind, state, stop_reason)
            """ + ENDED, nativeQuery = true)
    List<SettledRow> endExpired();

    /**
     * Ends an attempt whose lease expired when no other attempt may follow: the last attempt, or an at-most-once kind
     * whose write may have started.
     */
    @WorkspaceAgnostic("The reaper ends lost leases of every workspace")
    @Query(value = """
            WITH ended AS (
              UPDATE agent_action
                 SET state = CASE WHEN write_started_at IS NULL THEN 'FAILED' ELSE 'UNCERTAIN' END,
                     stop_reason = CASE WHEN write_started_at IS NULL THEN 'ATTEMPTS_EXHAUSTED'
                       ELSE 'LEASE_LOST_AFTER_WRITE' END,
                     payload = NULL, leased_until = NULL, ended_at = clock_timestamp(), updated_at = clock_timestamp()
               WHERE state = 'EXECUTING' AND leased_until <= clock_timestamp()
                 AND (attempts >= max_attempts OR (write_started_at IS NOT NULL AND kind IN (:atMostOnce)))
              RETURNING kind, state, stop_reason)
            """ + ENDED, nativeQuery = true)
    List<SettledRow> endLost(@Param("atMostOnce") Collection<String> atMostOnceKinds);

    /** Deletes what ended before the cutoff: the ledger of a sent effect is kept only as long as a replay can come. */
    @WorkspaceAgnostic("The retention sweep deletes ended actions of every workspace")
    @Modifying
    @Query(value = """
            DELETE FROM agent_action WHERE ended_at < clock_timestamp() - make_interval(days => :days)
            """, nativeQuery = true)
    int deleteEndedBefore(@Param("days") int retentionDays);
}
