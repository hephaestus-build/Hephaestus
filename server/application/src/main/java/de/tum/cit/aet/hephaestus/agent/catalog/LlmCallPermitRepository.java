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

    /**
     * Reclaim expired owners without refunding calls that already reached the provider.
     *
     * @return the permits whose owner stopped renewing them while their call was in flight
     */
    @Query(value = """
            WITH expired AS (
              UPDATE llm_call_permit SET state='CLOSED',closed_at=renewed_at+make_interval(secs => :staleSeconds)
              WHERE pool_id=:poolId AND state='ACTIVE'
                AND renewed_at < clock_timestamp()-make_interval(secs => :staleSeconds)
              RETURNING id
            )
            , deleted AS (
              DELETE FROM llm_call_permit WHERE pool_id=:poolId AND
                ((state='CLOSED' AND closed_at < clock_timestamp()-interval '1 minute') OR
                 (state='WAITING' AND renewed_at < clock_timestamp()-make_interval(secs => :staleSeconds)))
            )
            SELECT count(*) FROM expired
            """, nativeQuery = true)
    long prune(@Param("poolId") long poolId, @Param("staleSeconds") long staleSeconds);

    interface Head {
        UUID getId();

        String getCallClass();
    }

    /**
     * The next request of one queue. The workspace with the fewest calls in flight comes first, then, within it, the
     * service class and then the attempt, so that a wide review cannot hold more than its share while another waits.
     * Grant timestamps break ties independently of provider rate debits. Each group is read once: this runs under the
     * pool row lock, and a wide review can leave hundreds of requests waiting.
     */
    @Query(value = """
            WITH granted AS (
              SELECT workspace_id,source_id,call_class,state,granted_at FROM llm_call_permit
              WHERE pool_id=:poolId AND granted_at IS NOT NULL AND (call_class='TURN')=:interactive
            ), workspaces AS (
              SELECT workspace_id,count(*) FILTER (WHERE state='ACTIVE') AS active,max(granted_at) AS last
              FROM granted GROUP BY workspace_id
            ), classes AS (
              SELECT workspace_id,call_class='COMPOSITION' AS composition,max(granted_at) AS last
              FROM granted GROUP BY workspace_id,call_class='COMPOSITION'
            ), sources AS (
              SELECT source_id,count(*) FILTER (WHERE state='ACTIVE') AS active,max(granted_at) AS last
              FROM granted GROUP BY source_id
            )
            SELECT q.id AS "id",q.call_class AS "callClass" FROM llm_call_permit q
            LEFT JOIN workspaces w ON w.workspace_id=q.workspace_id
            LEFT JOIN classes c ON c.workspace_id=q.workspace_id AND c.composition=(q.call_class='COMPOSITION')
            LEFT JOIN sources s ON s.source_id=q.source_id
            WHERE q.pool_id=:poolId AND q.state='WAITING' AND (q.call_class='TURN')=:interactive
              AND (q.call_class<>'PRODUCT_FEEDBACK' OR NOT EXISTS (
                SELECT 1 FROM llm_call_permit h WHERE h.pool_id=q.pool_id
                  AND h.state='WAITING' AND h.call_class<>'PRODUCT_FEEDBACK'))
            ORDER BY COALESCE(w.active,0),w.last NULLS FIRST,c.last NULLS FIRST,
              COALESCE(s.active,0),s.last NULLS FIRST,q.acquired_at,q.id LIMIT 1
            """, nativeQuery = true)
    Optional<Head> next(@Param("poolId") long poolId, @Param("interactive") boolean interactive);

    /** Any worker may grant the head of the queue. The request's own worker finds the grant and makes the call. */
    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET state='ACTIVE',granted_at=clock_timestamp(),renewed_at=clock_timestamp(),requests=1,tokens=:tokens WHERE id=:id AND state='WAITING'",
            nativeQuery = true)
    int grant(@Param("id") UUID id, @Param("tokens") long tokens);

    interface Queued {
        UUID getId();

        String getState();
    }

    /** This worker's requests that still have a row: those that an admission round granted read ACTIVE. */
    @Query(
            value =
                    "SELECT id AS \"id\",state AS \"state\" FROM llm_call_permit WHERE worker_id=:workerId AND id IN (:ids)",
            nativeQuery = true)
    List<Queued> findQueued(@Param("workerId") String workerId, @Param("ids") Collection<UUID> ids);

    interface Backlog {
        String getSlug();

        int getLearnedLimit();

        @Nullable
        Integer getMaxConcurrentCalls();

        long getWaitingTurns();

        long getWaitingBackground();

        @Nullable
        Integer getBaselineFirstTokenMs();
    }

    /** What every pool in use stands at, whichever worker holds its calls, for its gauges. */
    @Query(value = """
            SELECT p.slug AS "slug",p.learned_limit AS "learnedLimit",p.max_concurrent_calls AS "maxConcurrentCalls",
              p.baseline_first_token_ms AS "baselineFirstTokenMs",
              count(*) FILTER (WHERE q.state='WAITING' AND q.call_class='TURN') AS "waitingTurns",
              count(*) FILTER (WHERE q.state='WAITING' AND q.call_class<>'TURN') AS "waitingBackground"
            FROM llm_call_pool p JOIN llm_call_permit q ON q.pool_id=p.id
            GROUP BY p.id,p.slug,p.learned_limit,p.max_concurrent_calls,p.baseline_first_token_ms
            """, nativeQuery = true)
    List<Backlog> backlog();

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
