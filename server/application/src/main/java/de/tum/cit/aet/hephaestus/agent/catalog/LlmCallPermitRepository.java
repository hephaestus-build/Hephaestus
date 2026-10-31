package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
            value = "SELECT count(*) FROM llm_call_permit WHERE pool_id = :poolId AND state='ACTIVE'",
            nativeQuery = true)
    long countActive(@Param("poolId") long poolId);

    @Query(
            value =
                    "SELECT count(*) FROM llm_call_permit WHERE pool_id = :poolId AND state='ACTIVE' AND call_class <> 'TURN'",
            nativeQuery = true)
    long countBackground(@Param("poolId") long poolId);

    record Request(
            UUID id,
            long poolId,
            String workerId,
            String callClass,
            @Nullable Long workspaceId,
            UUID sourceId,
            int attempt) {}

    @Query(
            value =
                    "SELECT count(*) FROM llm_call_permit WHERE pool_id=:poolId AND state='ACTIVE' AND call_class='PRODUCT_FEEDBACK'",
            nativeQuery = true)
    long countActiveProductFeedback(@Param("poolId") long poolId);

    @Modifying
    @Query(value = """
                    INSERT INTO llm_call_permit
                      (id,pool_id,worker_id,call_class,workspace_id,source_id,attempt,state,acquired_at,renewed_at,requests,tokens)
                    VALUES (:#{#request.id()},:#{#request.poolId()},:#{#request.workerId()},:#{#request.callClass()},
                      :#{#request.workspaceId()},:#{#request.sourceId()},:#{#request.attempt()},'WAITING',clock_timestamp(),clock_timestamp(),0,0)
                    """, nativeQuery = true)
    void enqueue(@Param("request") Request request);

    /** Uses database time and includes every workspace and worker in this endpoint pool. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM llm_call_permit q WHERE q.pool_id=p.id
              AND q.state='WAITING' AND q.call_class NOT IN ('TURN','PRODUCT_FEEDBACK')
              AND q.renewed_at>=clock_timestamp()-make_interval(secs => :staleSeconds))
            FROM llm_call_pool p WHERE p.id=:poolId
            """, nativeQuery = true)
    Optional<Boolean> hasBackgroundWaiters(@Param("poolId") long poolId, @Param("staleSeconds") long staleSeconds);

    /** Reclaim expired owners without refunding calls that already reached the provider. */
    @Modifying
    @Query(value = """
            WITH expired AS (
              UPDATE llm_call_permit SET state='CLOSED',closed_at=renewed_at+make_interval(secs => :staleSeconds)
              WHERE pool_id=:poolId AND state='ACTIVE'
                AND renewed_at < clock_timestamp()-make_interval(secs => :staleSeconds)
              RETURNING id
            )
            DELETE FROM llm_call_permit WHERE pool_id=:poolId AND
              ((state='CLOSED' AND closed_at < clock_timestamp()-interval '1 minute') OR
               (state='WAITING' AND renewed_at < clock_timestamp()-make_interval(secs => :staleSeconds)))
            """, nativeQuery = true)
    void prune(@Param("poolId") long poolId, @Param("staleSeconds") long staleSeconds);

    /**
     * Workspace, service class, then attempt each receive a turn before another grant to a busy peer.
     * Grant timestamps retain scheduling history independently of provider rate debits.
     */
    @Query(value = """
            SELECT q.id FROM llm_call_permit q
            WHERE q.pool_id=:poolId AND q.state='WAITING' AND (q.call_class='TURN')=:interactive
              AND (q.call_class<>'PRODUCT_FEEDBACK' OR NOT EXISTS (
                SELECT 1 FROM llm_call_permit h WHERE h.pool_id=q.pool_id
                  AND h.state='WAITING' AND h.call_class<>'PRODUCT_FEEDBACK'))
            ORDER BY
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.workspace_id=q.workspace_id AND (g.call_class='TURN')=:interactive) NULLS FIRST,
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.workspace_id=q.workspace_id AND (g.call_class='TURN')=:interactive AND (g.call_class='COMPOSITION')=(q.call_class='COMPOSITION')) NULLS FIRST,
              (SELECT max(g.granted_at) FROM llm_call_permit g WHERE g.pool_id=q.pool_id AND g.source_id=q.source_id AND (g.call_class='TURN')=:interactive) NULLS FIRST,
              q.acquired_at,q.id LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> next(@Param("poolId") long poolId, @Param("interactive") boolean interactive);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET state='ACTIVE',worker_id=:workerId,granted_at=clock_timestamp(),renewed_at=clock_timestamp(),requests=1,tokens=:tokens WHERE id=:id AND state='WAITING'",
            nativeQuery = true)
    int grant(@Param("id") UUID id, @Param("tokens") long tokens, @Param("workerId") String workerId);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET renewed_at=clock_timestamp() WHERE worker_id=:workerId AND id IN (:ids) AND state IN ('WAITING','ACTIVE') AND (state='WAITING' OR renewed_at >= clock_timestamp()-make_interval(secs => :staleSeconds))",
            nativeQuery = true)
    int renew(
            @Param("workerId") String workerId,
            @Param("ids") Collection<UUID> ids,
            @Param("staleSeconds") long staleSeconds);

    @Query(
            value =
                    "SELECT id FROM llm_call_permit WHERE worker_id=:workerId AND id IN (:ids) AND state='ACTIVE' AND renewed_at >= clock_timestamp()-make_interval(secs => :staleSeconds)",
            nativeQuery = true)
    List<UUID> findHeld(
            @Param("workerId") String workerId,
            @Param("ids") Collection<UUID> ids,
            @Param("staleSeconds") long staleSeconds);

    /** The UUID fences a release, including closure after lease expiry, from every later grant. */
    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET state='CLOSED',closed_at=clock_timestamp() WHERE id=:id AND worker_id=:workerId AND state='ACTIVE'",
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

    /** Active calls retain their complete rate debit. Closed calls retain it for a full minute after closure. */
    @Query(
            value =
                    "SELECT COALESCE(sum(requests),0) AS requests,COALESCE(sum(tokens),0) AS tokens,COALESCE(sum(requests) FILTER (WHERE call_class <> 'TURN'),0) AS \"backgroundRequests\",COALESCE(sum(tokens) FILTER (WHERE call_class <> 'TURN'),0) AS \"backgroundTokens\" FROM llm_call_permit WHERE pool_id=:poolId AND granted_at IS NOT NULL AND (closed_at IS NULL OR closed_at > clock_timestamp()-interval '1 minute')",
            nativeQuery = true)
    Rates rates(@Param("poolId") long poolId);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET requests=requests+1,tokens=tokens+:tokens WHERE id=:id AND state='ACTIVE' AND worker_id=:workerId AND renewed_at >= clock_timestamp()-make_interval(secs => :staleSeconds)",
            nativeQuery = true)
    int debitRetry(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("tokens") long tokens,
            @Param("staleSeconds") long staleSeconds);

    @Query(
            value = "SELECT count(*) FROM llm_call_permit WHERE pool_id=:poolId AND state <> 'CLOSED'",
            nativeQuery = true)
    long countUnclosed(@Param("poolId") long poolId);

    @Modifying
    @Query(value = "DELETE FROM llm_call_permit WHERE pool_id=:poolId AND state='CLOSED'", nativeQuery = true)
    void deleteClosed(@Param("poolId") long poolId);
}
