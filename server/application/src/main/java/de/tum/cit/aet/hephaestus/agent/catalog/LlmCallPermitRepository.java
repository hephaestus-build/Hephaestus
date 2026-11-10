package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
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

    /** The calls in flight that no person waits for: they may not use the interactive band's reserve. */
    @Query(
            value =
                    "SELECT count(*) FROM llm_call_permit WHERE pool_id = :poolId AND state='ACTIVE' AND band <> 'INTERACTIVE'",
            nativeQuery = true)
    long countNotInteractive(@Param("poolId") long poolId);

    record Request(
            UUID id,
            long poolId,
            String workerId,
            AgentRunBand band,
            @Nullable Long workspaceId,
            UUID sourceId,
            int attempt) {}

    /** Product feedback is the only call without a workspace. */
    @Query(
            value =
                    "SELECT count(*) FROM llm_call_permit WHERE pool_id=:poolId AND state='ACTIVE' AND workspace_id IS NULL",
            nativeQuery = true)
    long countActiveProductFeedback(@Param("poolId") long poolId);

    @Modifying
    @Query(value = """
                    INSERT INTO llm_call_permit
                      (id,pool_id,worker_id,band,workspace_id,source_id,attempt,state,acquired_at,renewed_at,requests,tokens)
                    VALUES (:#{#request.id()},:#{#request.poolId()},:#{#request.workerId()},:#{#request.band().name()},
                      :#{#request.workspaceId()},:#{#request.sourceId()},:#{#request.attempt()},'WAITING',clock_timestamp(),clock_timestamp(),0,0)
                    """, nativeQuery = true)
    void enqueue(@Param("request") Request request);

    /** PostgreSQL delivers the pool-id hint to every server only after the admission transaction commits. */
    @Query(value = "SELECT 1 FROM (SELECT pg_notify(:channel, :payload)) sent", nativeQuery = true)
    int notifyPool(@Param("channel") String channel, @Param("payload") String payload);

    /**
     * Whether a review's call waits: one that is not interactive and has a workspace. Uses database time and includes
     * every workspace and server in this endpoint pool.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM llm_call_permit q WHERE q.pool_id=p.id
              AND q.state='WAITING' AND q.band <> 'INTERACTIVE' AND q.workspace_id IS NOT NULL
              AND q.renewed_at>=clock_timestamp()-make_interval(secs => :staleSeconds))
            FROM llm_call_pool p WHERE p.id=:poolId
            """, nativeQuery = true)
    Optional<Boolean> hasReviewCallsWaiting(@Param("poolId") long poolId, @Param("staleSeconds") long staleSeconds);

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

    /**
     * The room of one admission round, which the caller computes under the pool row lock. Each value is how many grants
     * fit, and {@link Long#MAX_VALUE} has no bound.
     *
     * @param interactive the interactive calls that fit: under the limit, or within the reserve
     * @param limit the calls of any band that fit under the limit
     * @param notInteractive the calls that fit outside the reserve
     * @param productFeedback the product feedback calls that fit: at most one is in flight
     * @param rate the calls that fit in the pool's rate budgets
     * @param notInteractiveRate the calls that fit in the rate share outside the reserve
     */
    record Room(
            long interactive,
            long limit,
            long notInteractive,
            long productFeedback,
            long rate,
            long notInteractiveRate) {}

    /**
     * One admission round, in one statement: grants the longest prefix of the waiting calls, in grant order, that fits
     * the room. No call after a call that does not fit gets a grant, so no call passes one ahead of it.
     *
     * <p>The grant order is the band, then the workspace with the fewest granted calls in the pool, then the source with
     * the fewest granted calls, then age. A call that is not interactive rises one band for each {@code :agingSeconds}
     * that it waits, but never into the interactive band, as the agent run claim does. Product feedback comes after all
     * other calls. A round can grant several calls, so each call counts the calls ahead of it in its own workspace and
     * source as granted: apart from ties, the order is the one that one grant at a time would give. A tie goes to the
     * workspace whose last grant in the call's band was the oldest before the round, and then to the source whose last
     * grant was; a grant earlier in the same round does not move that time. The rows are locked in id order, as
     * {@link #renew} locks them, so a round and a renewal cannot deadlock. Each group is
     * read once: this runs under the pool row lock, and a wide review can leave hundreds of requests waiting.
     *
     * @return the calls granted
     */
    @Modifying
    @Query(value = """
            WITH granted AS (
              SELECT workspace_id,source_id,band,state,granted_at FROM llm_call_permit
              WHERE pool_id=:poolId AND granted_at IS NOT NULL
            ), workspaces AS (
              SELECT workspace_id,count(*) FILTER (WHERE state='ACTIVE') AS active FROM granted GROUP BY workspace_id
            ), recent AS (
              SELECT workspace_id,band,max(granted_at) AS last FROM granted GROUP BY workspace_id,band
            ), sources AS (
              SELECT source_id,count(*) FILTER (WHERE state='ACTIVE') AS active,max(granted_at) AS last
              FROM granted GROUP BY source_id
            ), waiting AS (
              SELECT id,workspace_id,source_id,band,acquired_at,workspace_id IS NULL AS feedback,
                CASE WHEN band='INTERACTIVE' THEN 0
                     ELSE GREATEST(1,(CASE band WHEN 'BATCH' THEN 1 ELSE 2 END)
                          - FLOOR(EXTRACT(EPOCH FROM (clock_timestamp()-acquired_at)) / :agingSeconds))
                END AS effective_band
              FROM llm_call_permit WHERE pool_id=:poolId AND state='WAITING'
            ), by_source AS (
              SELECT w.*,s.last AS source_last,COALESCE(s.active,0) + row_number() OVER (PARTITION BY w.source_id
                  ORDER BY w.feedback,w.effective_band,w.acquired_at,w.id) AS source_turn
              FROM waiting w LEFT JOIN sources s ON s.source_id=w.source_id
            ), turns AS (
              SELECT b.*,r.last AS workspace_last,COALESCE(ws.active,0) + row_number() OVER (PARTITION BY b.workspace_id
                  ORDER BY b.feedback,b.effective_band,b.source_turn,b.source_last NULLS FIRST,b.acquired_at,b.id)
                  AS workspace_turn
              FROM by_source b
              LEFT JOIN workspaces ws ON ws.workspace_id IS NOT DISTINCT FROM b.workspace_id
              LEFT JOIN recent r ON r.workspace_id IS NOT DISTINCT FROM b.workspace_id AND r.band=b.band
            ), ordered AS (
              SELECT id,band,feedback,row_number() OVER grant_order AS n,
                count(*) FILTER (WHERE band='INTERACTIVE') OVER grant_order AS interactive,
                count(*) FILTER (WHERE band<>'INTERACTIVE') OVER grant_order AS not_interactive,
                count(*) FILTER (WHERE feedback) OVER grant_order AS feedback_calls
              FROM turns
              WINDOW grant_order AS (ORDER BY feedback,effective_band,workspace_turn,workspace_last NULLS FIRST,
                source_turn,source_last NULLS FIRST,acquired_at,id ROWS UNBOUNDED PRECEDING)
            ), fitting AS (
              SELECT id,bool_and(fits) OVER (ORDER BY n ROWS UNBOUNDED PRECEDING) AS prefix_fits
              FROM (SELECT id,n,n<=:#{#room.rate()} AND CASE WHEN band='INTERACTIVE'
                       THEN interactive<=:#{#room.interactive()}
                       ELSE interactive+not_interactive<=:#{#room.limit()}
                         AND not_interactive<=:#{#room.notInteractive()}
                         AND not_interactive<=:#{#room.notInteractiveRate()}
                         AND feedback_calls<=:#{#room.productFeedback()}
                     END AS fits
                    FROM ordered) candidates
            ), locked AS (
              SELECT q.id FROM llm_call_permit q JOIN fitting f ON f.id=q.id
              WHERE f.prefix_fits AND q.state='WAITING' ORDER BY q.id FOR UPDATE OF q
            )
            UPDATE llm_call_permit p SET state='ACTIVE',granted_at=clock_timestamp(),renewed_at=clock_timestamp(),
              requests=1,tokens=:tokens,input_tokens=:tokens
            FROM locked l WHERE p.id=l.id AND p.state='WAITING'
            """, nativeQuery = true)
    int grantRound(
            @Param("poolId") long poolId,
            @Param("room") Room room,
            @Param("tokens") long tokens,
            @Param("agingSeconds") long agingSeconds);

    interface Queued {
        UUID getId();

        String getState();
    }

    /** This server's requests that still have a row: those that an admission round granted read ACTIVE. */
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

        long getWaitingInteractive();

        long getWaitingBatch();

        long getWaitingBackground();

        @Nullable
        Integer getBaselineFirstTokenMs();
    }

    /** What every pool in use stands at, whichever server holds its calls, for its gauges. */
    @Query(value = """
            SELECT p.slug AS "slug",p.learned_limit AS "learnedLimit",p.max_concurrent_calls AS "maxConcurrentCalls",
              p.baseline_first_token_ms AS "baselineFirstTokenMs",
              count(*) FILTER (WHERE q.state='WAITING' AND q.band='INTERACTIVE') AS "waitingInteractive",
              count(*) FILTER (WHERE q.state='WAITING' AND q.band='BATCH') AS "waitingBatch",
              count(*) FILTER (WHERE q.state='WAITING' AND q.band='BACKGROUND') AS "waitingBackground"
            FROM llm_call_pool p JOIN llm_call_permit q ON q.pool_id=p.id
            GROUP BY p.id,p.slug,p.learned_limit,p.max_concurrent_calls,p.baseline_first_token_ms
            """, nativeQuery = true)
    List<Backlog> backlog();

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET renewed_at=clock_timestamp() WHERE id IN (SELECT id FROM llm_call_permit WHERE worker_id=:workerId AND id IN (:ids) AND state IN ('WAITING','ACTIVE') AND (state='WAITING' OR renewed_at >= clock_timestamp()-make_interval(secs => :staleSeconds)) ORDER BY id FOR UPDATE)",
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

        long getNotInteractiveRequests();

        long getNotInteractiveTokens();

        long getInputTokens();

        long getOutputTokens();

        long getNotInteractiveInputTokens();

        long getNotInteractiveOutputTokens();
    }

    /** Active calls retain their complete rate debit. Closed calls retain it for a full minute after closure. */
    @Query(
            value =
                    "SELECT COALESCE(sum(requests),0) AS requests,COALESCE(sum(tokens),0) AS tokens,COALESCE(sum(input_tokens),0) AS \"inputTokens\",COALESCE(sum(output_tokens),0) AS \"outputTokens\",COALESCE(sum(requests) FILTER (WHERE band <> 'INTERACTIVE'),0) AS \"notInteractiveRequests\",COALESCE(sum(tokens) FILTER (WHERE band <> 'INTERACTIVE'),0) AS \"notInteractiveTokens\",COALESCE(sum(input_tokens) FILTER (WHERE band <> 'INTERACTIVE'),0) AS \"notInteractiveInputTokens\",COALESCE(sum(output_tokens) FILTER (WHERE band <> 'INTERACTIVE'),0) AS \"notInteractiveOutputTokens\" FROM llm_call_permit WHERE pool_id=:poolId AND granted_at IS NOT NULL AND (closed_at IS NULL OR closed_at > clock_timestamp()-interval '1 minute')",
            nativeQuery = true)
    Rates rates(@Param("poolId") long poolId);

    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET requests=requests+1,tokens=tokens+:tokens,input_tokens=input_tokens+:tokens WHERE id=:id AND state='ACTIVE' AND worker_id=:workerId AND renewed_at >= clock_timestamp()-make_interval(secs => :staleSeconds)",
            nativeQuery = true)
    int debitRetry(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("tokens") long tokens,
            @Param("staleSeconds") long staleSeconds);

    /** Replaces a call's debit by what its provider reported. A call without a report keeps its bound. */
    @Modifying
    @Query(
            value =
                    "UPDATE llm_call_permit SET tokens=:input+:output,input_tokens=:input,output_tokens=:output WHERE id=:id AND worker_id=:workerId",
            nativeQuery = true)
    int settleTokens(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("input") long input,
            @Param("output") long output);

    @Query(
            value = "SELECT count(*) FROM llm_call_permit WHERE pool_id=:poolId AND state <> 'CLOSED'",
            nativeQuery = true)
    long countUnclosed(@Param("poolId") long poolId);

    @Modifying
    @Query(value = "DELETE FROM llm_call_permit WHERE pool_id=:poolId AND state='CLOSED'", nativeQuery = true)
    void deleteClosed(@Param("poolId") long poolId);
}
