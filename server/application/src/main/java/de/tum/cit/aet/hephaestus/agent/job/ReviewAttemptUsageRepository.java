package de.tum.cit.aet.hephaestus.agent.job;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The measurement of each review job attempt and the charges the attempt froze. {@link ReviewAttemptUsageService} only. */
public interface ReviewAttemptUsageRepository extends JpaRepository<ReviewAttemptUsage, ReviewAttemptUsage.Id> {

    /** One purse's share of what running reviews have spent. */
    record RunningSpendRow(String fundingSource, BigDecimal usd) {}

    /**
     * What the running attempts of a workspace have spent, per purse, at their frozen rates. A practice script call on
     * the review's own model is already inside the attempt's proxy total. A model without a price adds nothing.
     */
    @NativeQuery("""
        WITH running AS (
          SELECT j.id,r.lease_epoch-1 AS attempt FROM review_job j
          JOIN agent_run r ON r.id=j.run_id AND r.workspace_id=j.workspace_id
          WHERE j.workspace_id=:workspaceId AND j.status='RUNNING'
        ), used AS (
          SELECT u.job_id,u.attempt,'CHAT' AS model_kind,u.proxy_input_tokens AS input_tokens,
            u.proxy_output_tokens AS output_tokens,u.proxy_cache_read_tokens AS cache_read_tokens,
            u.proxy_cache_write_tokens AS cache_write_tokens
          FROM review_job_attempt_usage u JOIN running r ON r.id=u.job_id AND r.attempt=u.attempt
          WHERE u.workspace_id=:workspaceId
          UNION ALL
          SELECT s.job_id,s.attempt,s.model_kind,s.input_tokens,s.output_tokens,s.cache_read_tokens,s.cache_write_tokens
          FROM review_job_script_usage s JOIN running r ON r.id=s.job_id AND r.attempt=s.attempt
          WHERE s.workspace_id=:workspaceId AND s.model_kind<>'CHAT'
        )
        SELECT c.funding_source,
          SUM(x.input_tokens*COALESCE(c.applied_per_1m_input_usd,0)
            +x.output_tokens*COALESCE(c.applied_per_1m_output_usd,0)
            +x.cache_read_tokens*COALESCE(c.applied_per_1m_cache_read_usd,0)
            +x.cache_write_tokens*COALESCE(c.applied_per_1m_cache_write_usd,0))/1000000
        FROM used x JOIN review_job_attempt_charge c
          ON c.job_id=x.job_id AND c.attempt=x.attempt AND c.model_kind=x.model_kind
        WHERE c.workspace_id=:workspaceId AND c.pricing_state='PRICED'
        GROUP BY c.funding_source
        """)
    List<RunningSpendRow> sumRunningSpend(@Param("workspaceId") long workspaceId);

    /** Taken after the job row, never before it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM ReviewAttemptUsage u WHERE u.workspaceId = :workspaceId AND u.id.jobId = :jobId"
            + " AND u.id.attempt = :attempt")
    Optional<ReviewAttemptUsage> lock(
            @Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId, @Param("attempt") int attempt);

    /**
     * Creates the attempt's measurement, empty, when the proxy authenticates its first call. Creating it counts no
     * call. The caller holds the job row, which the foreign key needs to exist.
     *
     * <p>No conflict target: a targeted {@code ON CONFLICT} arbitrates only its own index, and concurrent first calls
     * then fail on the three-column key that the charges reference.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO review_job_attempt_usage
            (job_id, attempt, workspace_id, proxy_calls, proxy_input_tokens, proxy_output_tokens,
             proxy_reasoning_tokens, proxy_cache_read_tokens, proxy_cache_write_tokens)
        VALUES (:jobId, :attempt, :workspaceId, 0, 0, 0, 0, 0, 0)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int registerIfAbsent(
            @Param("jobId") UUID jobId, @Param("attempt") int attempt, @Param("workspaceId") long workspaceId);

    /** Removes every attempt's charges of one job; the caller holds the job row. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM review_job_attempt_charge WHERE workspace_id = :workspaceId AND job_id = :jobId",
            nativeQuery = true)
    int deleteCharges(@Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId);

    /** Removes every attempt's measurement of one job, after its charges; the caller holds the job row. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM review_job_attempt_usage WHERE workspace_id = :workspaceId AND job_id = :jobId",
            nativeQuery = true)
    int deleteMeasurements(@Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId);

    /** Freezes one model kind's charge; the first call of that kind wins and later ones change nothing. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO review_job_attempt_charge
            (job_id, attempt, model_kind, workspace_id, model, funding_source, pricing_state, applied_price_id,
             applied_workspace_model_id, applied_per_1m_input_usd, applied_per_1m_output_usd,
             applied_per_1m_cache_read_usd, applied_per_1m_cache_write_usd)
        VALUES (:jobId, :attempt, :modelKind, :workspaceId, :#{#charge.model()},
            :#{#charge.price().fundingSource().name()}, :#{#charge.price().pricingState().name()},
            :#{#charge.price().appliedPriceId()}, :#{#charge.price().appliedWorkspaceModelId()},
            :#{#charge.price().per1mInputUsd()}, :#{#charge.price().per1mOutputUsd()},
            :#{#charge.price().per1mCacheReadUsd()}, :#{#charge.price().per1mCacheWriteUsd()})
        ON CONFLICT (job_id, attempt, model_kind) DO NOTHING
        """, nativeQuery = true)
    int freezeChargeIfAbsent(
            @Param("jobId") UUID jobId,
            @Param("attempt") int attempt,
            @Param("modelKind") String modelKind,
            @Param("workspaceId") long workspaceId,
            @Param("charge") FrozenCharge charge);

    @Query("SELECT c FROM ReviewAttemptCharge c WHERE c.workspaceId = :workspaceId AND c.id.jobId = :jobId"
            + " AND c.id.attempt = :attempt")
    List<ReviewAttemptCharge> findCharges(
            @Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId, @Param("attempt") int attempt);
}
