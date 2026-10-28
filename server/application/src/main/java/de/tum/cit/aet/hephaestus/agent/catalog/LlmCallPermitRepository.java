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
            UUID id, long poolId, String workerId, String callClass, long workspaceId, UUID sourceId, int attempt) {}

    @Modifying
    @Query(value = """
                    INSERT INTO llm_call_permit
                      (id,pool_id,worker_id,call_class,workspace_id,source_id,attempt,state,acquired_at,renewed_at,requests,tokens)
                    VALUES (:#{#request.id()},:#{#request.poolId()},:#{#request.workerId()},:#{#request.callClass()},
                      :#{#request.workspaceId()},:#{#request.sourceId()},:#{#request.attempt()},'WAITING',clock_timestamp(),clock_timestamp(),0,0)
                    """, nativeQuery = true)
    void enqueue(@Param("request") Request request);

    /** Old queued requests never reached a provider. Active requests are never reclaimed by time. */
    @Modifying
    @Query(
            value =
                    "DELETE FROM llm_call_permit WHERE pool_id=:poolId AND ((state='CLOSED' AND closed_at < clock_timestamp()-interval '1 minute') OR (state='WAITING' AND renewed_at < clock_timestamp()-make_interval(secs => :staleSeconds)))",
            nativeQuery = true)
    void prune(@Param("poolId") long poolId, @Param("staleSeconds") long staleSeconds);

    /** Workspace, service class, then attempt each receive a turn before another grant to a busy peer. */
    @Query(value = """
            SELECT q.id FROM llm_call_permit q
            WHERE q.pool_id=:poolId AND q.state='WAITING' AND (q.call_class='TURN')=:interactive
            ORDER BY
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.workspace_id=q.workspace_id AND g.requests>0) NULLS FIRST,
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.workspace_id=q.workspace_id AND (g.call_class='COMPOSITION')=(q.call_class='COMPOSITION') AND g.requests>0) NULLS FIRST,
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.source_id=q.source_id AND g.requests>0) NULLS FIRST,
              q.acquired_at,q.id LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> next(@Param("poolId") long poolId, @Param("interactive") boolean interactive);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET state=:state,granted_at=clock_timestamp(),renewed_at=clock_timestamp(),requests=1,tokens=:tokens WHERE id=:id AND state='WAITING'",
            nativeQuery = true)
    int grant(@Param("id") UUID id, @Param("state") String state, @Param("tokens") long tokens);

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
