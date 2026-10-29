package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Instance-owned scheduling metadata. Every grant and rate debit holds the pool row lock. */
@Repository
@WorkspaceAgnostic("Fleet-wide endpoint admission and rate accounting, shared across workspaces.")
public interface LlmCallPermitRepository extends JpaRepository<LlmCallPermit, UUID> {

    @Query(
            value = "SELECT count(*) FROM llm_call_permit WHERE pool_id = :poolId AND state IN ('RESERVED','ACTIVE')",
            nativeQuery = true)
    long countActive(@Param("poolId") long poolId);

    @Query(
            value =
                    "SELECT count(*) FROM llm_call_permit WHERE pool_id = :poolId AND state IN ('RESERVED','ACTIVE') AND call_class <> 'TURN'",
            nativeQuery = true)
    long countBackground(@Param("poolId") long poolId);

    record Request(
            UUID id,
            long poolId,
            String workerId,
            String callClass,
            long workspaceId,
            UUID sourceId,
            int attempt,
            String state) {}

    @Modifying
    @Query(value = """
                    INSERT INTO llm_call_permit
                      (id,pool_id,worker_id,call_class,workspace_id,source_id,attempt,state,acquired_at,renewed_at,requests,tokens)
                    VALUES (:#{#request.id()},:#{#request.poolId()},:#{#request.workerId()},:#{#request.callClass()},
                      :#{#request.workspaceId()},:#{#request.sourceId()},:#{#request.attempt()},:#{#request.state()},clock_timestamp(),clock_timestamp(),0,0)
                    """, nativeQuery = true)
    void enqueue(@Param("request") Request request);

    /** Unused reservations can expire across workers. ACTIVE rows require transport-closure proof. */
    @Modifying
    @Query(value = """
            WITH expired AS (
              UPDATE llm_call_permit SET state='CLOSED',closed_at=clock_timestamp(),requests=0,tokens=0
              WHERE pool_id=:poolId AND state='RESERVED'
                AND renewed_at < clock_timestamp()-make_interval(secs => :staleSeconds)
              RETURNING id
            )
            DELETE FROM llm_call_permit WHERE pool_id=:poolId AND
              ((state='CLOSED' AND closed_at < clock_timestamp()-interval '1 minute') OR
               (state IN ('WAITING','CLAIM_WAITING') AND renewed_at < clock_timestamp()-make_interval(secs => :staleSeconds)) OR
               (state='CLAIM_WAITING' AND NOT EXISTS (SELECT 1 FROM review_job j JOIN workspace w ON w.id=j.workspace_id
                 WHERE j.id=llm_call_permit.source_id AND j.workspace_id=llm_call_permit.workspace_id
                   AND j.attempt=llm_call_permit.attempt AND j.status='QUEUED'
                   AND j.available_at<=clock_timestamp() AND w.status='ACTIVE')))
            """, nativeQuery = true)
    void prune(@Param("poolId") long poolId, @Param("staleSeconds") long staleSeconds);

    @Query(value = """
            SELECT id FROM llm_call_permit WHERE pool_id=:poolId AND source_id=:sourceId
              AND workspace_id=:workspaceId AND attempt=:attempt AND state='CLAIM_WAITING' FOR UPDATE
            """, nativeQuery = true)
    Optional<UUID> waitingClaim(
            @Param("poolId") long poolId,
            @Param("sourceId") UUID sourceId,
            @Param("workspaceId") long workspaceId,
            @Param("attempt") int attempt);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET worker_id=:workerId,renewed_at=clock_timestamp() WHERE id=:id AND state='CLAIM_WAITING'",
            nativeQuery = true)
    void pollClaim(@Param("id") UUID id, @Param("workerId") String workerId);

    /** Workspace, service class, then attempt each receive a turn before another grant to a busy peer. */
    @Query(value = """
            SELECT q.id FROM llm_call_permit q
            WHERE q.pool_id=:poolId AND q.state IN ('WAITING','CLAIM_WAITING') AND (q.call_class='TURN')=:interactive
            ORDER BY
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.workspace_id=q.workspace_id AND (g.call_class='TURN')=:interactive AND g.requests>0) NULLS FIRST,
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.workspace_id=q.workspace_id AND (g.call_class='TURN')=:interactive AND (g.call_class='COMPOSITION')=(q.call_class='COMPOSITION') AND g.requests>0) NULLS FIRST,
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.source_id=q.source_id AND (g.call_class='TURN')=:interactive AND g.requests>0) NULLS FIRST,
              q.acquired_at,q.id LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> next(@Param("poolId") long poolId, @Param("interactive") boolean interactive);

    @Query(value = "SELECT source_id FROM llm_call_permit WHERE id=:id AND state='CLAIM_WAITING'", nativeQuery = true)
    Optional<UUID> claimJobId(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            DELETE FROM llm_call_permit WHERE workspace_id=:workspaceId AND source_id=:sourceId
              AND attempt=:attempt AND state='CLAIM_WAITING'
            """, nativeQuery = true)
    void withdrawClaim(
            @Param("workspaceId") long workspaceId, @Param("sourceId") UUID sourceId, @Param("attempt") int attempt);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET state=:state,worker_id=:workerId,granted_at=clock_timestamp(),renewed_at=clock_timestamp(),requests=1,tokens=:tokens WHERE id=:id AND state IN ('WAITING','CLAIM_WAITING')",
            nativeQuery = true)
    int grant(
            @Param("id") UUID id,
            @Param("state") String state,
            @Param("tokens") long tokens,
            @Param("workerId") String workerId);

    @Query(
            value =
                    "SELECT id FROM llm_call_permit WHERE pool_id=:poolId AND source_id=:sourceId AND workspace_id=:workspaceId AND attempt=:attempt AND worker_id=:workerId AND state='RESERVED' ORDER BY acquired_at LIMIT 1 FOR UPDATE",
            nativeQuery = true)
    Optional<UUID> reservation(
            @Param("poolId") long poolId,
            @Param("sourceId") UUID sourceId,
            @Param("attempt") int attempt,
            @Param("workerId") String workerId,
            @Param("workspaceId") long workspaceId);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET state='ACTIVE',renewed_at=clock_timestamp() WHERE id=:id AND state='RESERVED' AND worker_id=:workerId",
            nativeQuery = true)
    int activate(@Param("id") UUID id, @Param("workerId") String workerId);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET renewed_at=clock_timestamp() WHERE worker_id=:workerId AND id IN (:ids) AND state IN ('WAITING','RESERVED','ACTIVE')",
            nativeQuery = true)
    int renew(@Param("workerId") String workerId, @Param("ids") Collection<UUID> ids);

    @Query(
            value =
                    "SELECT id FROM llm_call_permit WHERE worker_id=:workerId AND id IN (:ids) AND state IN ('RESERVED','ACTIVE')",
            nativeQuery = true)
    List<UUID> findHeld(@Param("workerId") String workerId, @Param("ids") Collection<UUID> ids);

    /** The UUID is the fence. A release can affect only this request, never a later attempt or grant. */
    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET requests=CASE WHEN state='RESERVED' THEN 0 ELSE requests END,tokens=CASE WHEN state='RESERVED' THEN 0 ELSE tokens END,state='CLOSED',closed_at=clock_timestamp() WHERE id=:id AND worker_id=:workerId AND state IN ('RESERVED','ACTIVE')",
            nativeQuery = true)
    int release(@Param("id") UUID id, @Param("workerId") String workerId);

    /** Only unused first-call reservations can be relinquished without transport-closure proof. */
    @Query(value = """
            WITH released AS (
              UPDATE llm_call_permit SET state='CLOSED',closed_at=clock_timestamp(),requests=0,tokens=0
              WHERE worker_id=:workerId AND workspace_id=:workspaceId AND source_id=:sourceId
                AND attempt=:attempt AND pool_id<>:requestedPool AND state='RESERVED'
              RETURNING id
            ) SELECT id FROM released
            """, nativeQuery = true)
    List<UUID> releaseOtherReservations(
            @Param("requestedPool") long requestedPool,
            @Param("workerId") String workerId,
            @Param("workspaceId") long workspaceId,
            @Param("sourceId") UUID sourceId,
            @Param("attempt") int attempt);

    @Modifying
    @Query(
            value = "DELETE FROM llm_call_permit WHERE id=:id AND worker_id=:workerId AND state='WAITING'",
            nativeQuery = true)
    void abandon(@Param("id") UUID id, @Param("workerId") String workerId);

    interface Rates {
        long getRequests();

        long getTokens();

        long getBackgroundRequests();

        long getBackgroundTokens();
    }

    /** Active calls retain their complete reservation. Closed calls retain it for a full minute after closure. */
    @Query(
            value =
                    "SELECT COALESCE(sum(requests),0) AS requests,COALESCE(sum(tokens),0) AS tokens,COALESCE(sum(requests) FILTER (WHERE call_class <> 'TURN'),0) AS \"backgroundRequests\",COALESCE(sum(tokens) FILTER (WHERE call_class <> 'TURN'),0) AS \"backgroundTokens\" FROM llm_call_permit WHERE pool_id=:poolId AND granted_at IS NOT NULL AND (closed_at IS NULL OR closed_at > clock_timestamp()-interval '1 minute')",
            nativeQuery = true)
    Rates rates(@Param("poolId") long poolId);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET requests=requests+1,tokens=tokens+:tokens WHERE id=:id AND state='ACTIVE' AND worker_id=:workerId",
            nativeQuery = true)
    int debitRetry(@Param("id") UUID id, @Param("workerId") String workerId, @Param("tokens") long tokens);

    @Query(
            value = "SELECT count(*) FROM llm_call_permit WHERE pool_id=:poolId AND state <> 'CLOSED'",
            nativeQuery = true)
    long countUnclosed(@Param("poolId") long poolId);

    @Query(
            value =
                    "SELECT p.id FROM llm_call_permit p WHERE p.worker_id=:workerId AND p.state='RESERVED' AND NOT EXISTS (SELECT 1 FROM review_job j WHERE j.id=p.source_id AND j.workspace_id=p.workspace_id AND j.attempt=p.attempt AND j.worker_id=p.worker_id AND j.status='RUNNING' AND j.leased_until>clock_timestamp())",
            nativeQuery = true)
    List<UUID> abandonedReservations(@Param("workerId") String workerId);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET state='CLOSED',closed_at=clock_timestamp(),requests=0,tokens=0 WHERE id=:id AND worker_id=:workerId AND state='RESERVED'",
            nativeQuery = true)
    int releaseReservation(@Param("id") UUID id, @Param("workerId") String workerId);

    @Modifying
    @Query(value = "DELETE FROM llm_call_permit WHERE pool_id=:poolId AND state='CLOSED'", nativeQuery = true)
    void deleteClosed(@Param("poolId") long poolId);
}
