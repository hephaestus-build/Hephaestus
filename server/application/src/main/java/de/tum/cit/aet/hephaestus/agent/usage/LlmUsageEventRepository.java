package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmUsageEventRepository extends JpaRepository<LlmUsageEvent, UUID> {
    /**
     * The runs among the ledger rows of {@code e}: one per job attempt and one per mentor turn attempt. Every call of an
     * attempt, a practice script call included, carries the job or turn id and the attempt number, so it counts toward
     * that run and never as a run of its own.
     */
    String RUNS = "COUNT(DISTINCT (e.source_id, e.source_attempt)) FILTER (WHERE e.id IS NOT NULL)";

    /**
     * Whether ledger row {@code e} counts at its upper bound ({@code cost_bound_usd}): its call is still open, or it
     * has no price, which a call whose usage was not measured never gets. Such a row is never an exact cost.
     */
    String BOUNDED = "(e.cost_bound_usd IS NOT NULL AND (e.call_state = 'OPEN' OR e.pricing_state = 'UNPRICED'))";

    /** Whether nobody knows what ledger row {@code e} cost: it has no price and no upper bound. */
    String UNKNOWN = "(e.pricing_state = 'UNPRICED' AND e.cost_bound_usd IS NULL)";

    /**
     * What ledger row {@code e} adds to a spend total: its upper bound while it is {@link #BOUNDED}, else its measured
     * cost. {@code NULL} only for an {@link #UNKNOWN} row. Every spend read that a cap or a budget compares uses it, so
     * no call can make a cap or a review unverifiable once its bound is known.
     */
    String SPEND =
            "(CASE WHEN " + BOUNDED + " THEN e.cost_bound_usd WHEN e.pricing_state <> 'UNPRICED' THEN e.cost_usd END)";

    /**
     * The measured cost that ledger row {@code e} adds to confirmed spend: its cost once it has a price and does not
     * count at its upper bound. {@link #SPEND} is this plus the bound of each {@link #BOUNDED} row.
     */
    String CONFIRMED = "(CASE WHEN NOT " + BOUNDED + " AND e.pricing_state <> 'UNPRICED' THEN e.cost_usd END)";

    /**
     * The runs among the ledger rows of {@code e} that have at least one row whose cost is {@link #UNKNOWN}. A practice
     * script row carries the job id and attempt of its run, so it counts toward that run and never as a run of its own.
     */
    String UNPRICED_RUNS = "COUNT(DISTINCT (e.source_id, e.source_attempt)) FILTER (WHERE " + UNKNOWN + ")";

    /**
     * Whether workspace {@code w} has an enabled own-provider model on an enabled own-provider
     * connection. No binding to that model is needed. The instance policy on own providers is not
     * part of it, because models that are already connected keep working after an instance admin
     * turns that policy off.
     */
    String OWN_PROVIDER_CONNECTED =
            "EXISTS (SELECT 1 FROM workspace_llm_model m JOIN workspace_llm_connection c ON c.id ="
                    + " m.connection_id AND c.workspace_id = m.workspace_id WHERE m.workspace_id = w.id"
                    + " AND m.enabled AND c.enabled)";

    @WorkspaceAgnostic("Retention removes expired usage rows across all workspaces")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM llm_usage_event WHERE id IN (  SELECT e.id FROM llm_usage_event e"
                    + " WHERE e.occurred_at < :cutoff LIMIT :batchSize)",
            nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Writes one call {@code OPEN}, dated by the database clock, which every lease compares with. Its measurement is
     * zero until the call ends; {@code costUsd} is zero for a priced or free model and {@code null} for an unpriced one.
     * The call's upper bound counts in its place ({@link #SPEND}).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO llm_usage_event (
            id, workspace_id, run_id, lease_epoch, action_id, settle_after, initial_pricing_state, call_state, job_type, source_type, source_id, source_attempt,
            review_id, model_kind, practice_id, model, cost_usd, pricing_state, funding_source, applied_price_id,
            applied_workspace_model_id, applied_per_1m_input_usd, applied_per_1m_output_usd,
            applied_per_1m_cache_read_usd, applied_per_1m_cache_write_usd, cost_bound_usd, total_calls, started_at,
            occurred_at
        ) VALUES (
            :id, :#{#call.workspaceId()}, :#{#call.runId()}, :#{#call.leaseEpoch()}, :#{#call.actionId()}, :#{#call.settleAfter()}, :#{#call.price().pricingState().name()}, 'OPEN', :#{#call.jobType()},
            :#{#call.sourceType().name()}, :#{#call.sourceId()}, :#{#call.sourceAttempt()}, :#{#call.reviewId()},
            :#{#call.modelKind().name()}, :#{#call.practiceId()}, :#{#call.model()}, :costUsd,
            :#{#call.price().pricingState().name()}, :#{#call.price().fundingSource().name()},
            :#{#call.price().appliedPriceId()}, :#{#call.price().appliedWorkspaceModelId()},
            :#{#call.price().per1mInputUsd()}, :#{#call.price().per1mOutputUsd()},
            :#{#call.price().per1mCacheReadUsd()}, :#{#call.price().per1mCacheWriteUsd()}, :#{#call.costBoundUsd()},
            1, clock_timestamp(), clock_timestamp()
        )
        """, nativeQuery = true)
    int insertOpen(
            @Param("id") UUID id,
            @Param("call") LlmUsageRecorder.OpenCall call,
            @Param("costUsd") @Nullable BigDecimal costUsd);

    String SETTLE_INSTANCE =
            "UPDATE llm_usage_event e SET call_state='SETTLED', measurement_verification='UNVERIFIABLE',"
                    + " pricing_state='UNPRICED', cost_usd=NULL, unreported_calls=1"
                    + " WHERE e.workspace_id IS NULL AND e.call_state='OPEN' AND e.source_type='PRODUCT_FEEDBACK_DRAFT'";

    @WorkspaceAgnostic("Instance action completion settles only the calls owned by that action")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = SETTLE_INSTANCE + " AND e.action_id=:actionId", nativeQuery = true)
    int settleAction(@Param("actionId") UUID actionId);

    @WorkspaceAgnostic("Recovery settles abandoned instance calls; workspace calls remain run-owned")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = SETTLE_INSTANCE + " AND (e.settle_after <= clock_timestamp() OR NOT EXISTS ("
                    + " SELECT 1 FROM agent_action a WHERE a.id=e.action_id AND a.state='EXECUTING'"
                    + " AND a.leased_until > clock_timestamp()))",
            nativeQuery = true)
    int settleAbandonedInstanceCalls();

    /** The call a measurement replaces, held until the measurement commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT e FROM LlmUsageEvent e WHERE e.id = :id AND (e.workspaceId = :workspaceId OR (e.workspaceId IS NULL AND :workspaceId IS NULL))")
    Optional<LlmUsageEvent> lockCall(@Param("id") UUID id, @Param("workspaceId") @Nullable Long workspaceId);

    /** Records the first failure only, including on a call whose usage was measured or settled already. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE llm_usage_event
        SET call_failure = jsonb_build_object('kind', CAST(:kind AS text), 'status', CAST(:status AS integer)),
            failed_at = clock_timestamp()
        WHERE id = :id AND workspace_id IS NOT DISTINCT FROM :workspaceId AND call_failure IS NULL
        """, nativeQuery = true)
    int recordFailure(
            @Param("id") UUID id,
            @Param("workspaceId") @Nullable Long workspaceId,
            @Param("kind") String kind,
            @Param("status") @Nullable Integer status);

    /** The newest failure of this claim, rather than the call that opened last. */
    @NativeQuery(value = """
        SELECT e.call_failure FROM llm_usage_event e
        WHERE e.workspace_id = :workspaceId AND e.run_id = :runId AND e.lease_epoch = :epoch
          AND e.call_failure IS NOT NULL
        ORDER BY e.failed_at DESC, e.id DESC LIMIT 1
        """)
    Optional<MentorTurn.FailedCall> latestFailure(
            @Param("workspaceId") long workspaceId, @Param("runId") UUID runId, @Param("epoch") int epoch);

    /**
     * Ends one call with its measurement and nothing else; see {@link LlmUsageRecorder#complete}. A {@code null} cost
     * leaves the call unpriced. An unverifiable call is one call without reported usage.
     *
     * The measurement keeps the call's upper bound unless it found a missing price rate.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE llm_usage_event SET call_state = 'ENDED', input_tokens = :#{#measurement.usage().inputTokens()},
            output_tokens = :#{#measurement.usage().outputTokens()}, cache_read_tokens = :#{#measurement.usage().cacheReadTokens()},
            cache_write_tokens = :#{#measurement.usage().cacheWriteTokens()}, reasoning_tokens = :#{#measurement.usage().reasoningTokens()},
            search_units = :#{#measurement.usage().searchUnits()}, usage_provenance = 'PROXY',
            unreported_calls = CASE WHEN CAST(:#{#measurement.verification().name()} AS text) = 'UNVERIFIABLE' THEN 1 ELSE 0 END,
            measurement_verification = :#{#measurement.verification().name()},
            pricing_state = :#{#measurement.pricingState().name()},
            cost_usd = :#{#measurement.costUsd()}, cost_bound_usd = CASE WHEN :#{#measurement.keepBound()} THEN cost_bound_usd END
        WHERE id = :id AND workspace_id IS NOT DISTINCT FROM :workspaceId
        """, nativeQuery = true)
    int measure(
            @Param("id") UUID id,
            @Param("workspaceId") @Nullable Long workspaceId,
            @Param("measurement") LlmUsageRecorder.Measurement measurement);

    /**
     * The calls of one review attempt on the review's own model, those without reported usage and those still open
     * included, and the output tokens of the ended ones.
     */
    @Query(value = """
        SELECT COUNT(*) AS calls, COALESCE(SUM(e.output_tokens), 0) AS outputTokens
        FROM llm_usage_event e
        WHERE e.workspace_id = :workspaceId AND e.source_type = 'REVIEW_JOB' AND e.source_id = :jobId
          AND e.source_attempt = :attempt
        """, nativeQuery = true)
    ReviewCalls countReviewCalls(
            @Param("workspaceId") long workspaceId, @Param("jobId") UUID jobId, @Param("attempt") int attempt);

    /**
     * What one attempt's ended calls of a source cost; {@code null} when it made no call or one of its calls has no
     * price.
     */
    @Query(value = """
        SELECT CASE WHEN bool_or(e.pricing_state = 'UNPRICED') THEN NULL ELSE SUM(e.cost_usd) END
        FROM llm_usage_event e
        WHERE e.workspace_id = :workspaceId AND e.source_type = :sourceType AND e.source_id = :sourceId
          AND e.source_attempt = :attempt
        """, nativeQuery = true)
    @Nullable
    BigDecimal sumAttemptCost(
            @Param("workspaceId") long workspaceId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") UUID sourceId,
            @Param("attempt") int attempt);

    interface ReviewCalls {
        long getCalls();

        long getOutputTokens();
    }

    /**
     * The sum the instance cap compares against ({@link #SPEND}): measured cost, and the upper bound of each call that
     * is open or was not measured. Own-provider spend is a different purse.
     */
    @Query(
            value = "SELECT COALESCE(SUM(" + SPEND + "), 0) FROM llm_usage_event e WHERE"
                    + " e.workspace_id = :workspaceId AND e.occurred_at >= :from AND"
                    + " e.occurred_at < :to AND e.funding_source = 'INSTANCE'",
            nativeQuery = true)
    BigDecimal sumCost(@Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * The sum the workspace's own-provider cap compares against — never summed into the instance
     * total, the two are different people's money.
     */
    @Query(
            value = "SELECT COALESCE(SUM(" + SPEND + "), 0) FROM llm_usage_event e WHERE"
                    + " e.workspace_id = :workspaceId AND e.occurred_at >= :from AND"
                    + " e.occurred_at < :to AND e.funding_source = 'WORKSPACE'",
            nativeQuery = true)
    BigDecimal sumByoCost(@Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * The runs ({@link #UNPRICED_RUNS}) that either sum leaves out in part, because one of their rows has neither a
     * price nor an upper bound, so the blind spot is visible.
     *
     * <p>Reads {@code pricing_state}, not {@code cost_usd IS NULL}: a CHECK constraint keeps the two together, and the
     * state column is what {@link #existsUnpricedInstanceFunded}, the read that blocks, reads too.
     */
    @Query(
            value = "SELECT "
                    + UNPRICED_RUNS
                    + " FROM llm_usage_event e WHERE e.workspace_id = :workspaceId AND"
                    + " e.occurred_at >= :from AND e.occurred_at < :to",
            nativeQuery = true)
    long countUnpricedRuns(
            @Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Every ended UNPRICED row with a verified measurement, no unreported calls and at least one billable token, in
     * every month and across all workspaces — the repricing pass's work list. Incomplete usage is unknown spend that no
     * rate repairs, so it is never selected and cannot hold a page against the rows behind it. A partial index holds
     * exactly these rows.
     *
     * <p>Ordered oldest first so a backlog is cleared in the order it accumulated, and paged because one catalogue gap
     * can leave a month's worth of rows behind it.
     */
    @WorkspaceAgnostic("Repricing pass enumerates the instance's unpriced spend across all tenants")
    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.usage.UnpricedLedgerRow(e.id, e.workspaceId, e.model,"
            + " e.fundingSource, e.inputTokens, e.outputTokens, e.cacheReadTokens, e.cacheWriteTokens, e.searchUnits,"
            + " e.appliedPriceId, e.appliedWorkspaceModelId, e.measurementVerification, e.initialPricingState,"
            + " e.appliedPer1mInputUsd, e.appliedPer1mOutputUsd, e.appliedPer1mCacheReadUsd, e.appliedPer1mCacheWriteUsd) FROM LlmUsageEvent e WHERE"
            + " e.pricingState ="
            + " de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState.UNPRICED AND e.measurementVerification ="
            + " de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification.VERIFIED AND e.unreportedCalls = 0 AND"
            + " (e.inputTokens > 0 OR e.outputTokens > 0 OR e.cacheReadTokens > 0 OR e.cacheWriteTokens > 0) ORDER BY"
            + " e.occurredAt, e.id")
    List<UnpricedLedgerRow> findUnpriced(Pageable pageable);

    /** {@link #findUnpriced} after a row of an earlier pass, so rows that stay unpriced cannot hold every pass. */
    @WorkspaceAgnostic("Repricing pass enumerates the instance's unpriced spend across all tenants")
    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.usage.UnpricedLedgerRow(e.id, e.workspaceId, e.model,"
            + " e.fundingSource, e.inputTokens, e.outputTokens, e.cacheReadTokens, e.cacheWriteTokens, e.searchUnits,"
            + " e.appliedPriceId, e.appliedWorkspaceModelId, e.measurementVerification, e.initialPricingState,"
            + " e.appliedPer1mInputUsd, e.appliedPer1mOutputUsd, e.appliedPer1mCacheReadUsd, e.appliedPer1mCacheWriteUsd) FROM LlmUsageEvent e WHERE"
            + " e.pricingState ="
            + " de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState.UNPRICED AND e.measurementVerification ="
            + " de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification.VERIFIED AND e.unreportedCalls = 0 AND"
            + " (e.inputTokens > 0 OR e.outputTokens > 0 OR e.cacheReadTokens > 0 OR e.cacheWriteTokens > 0) AND (e.occurredAt > :afterAt OR (e.occurredAt = :afterAt AND e.id > :afterId)) ORDER BY"
            + " e.occurredAt, e.id")
    List<UnpricedLedgerRow> findUnpricedAfter(
            @Param("afterAt") Instant afterAt, @Param("afterId") UUID afterId, Pageable pageable);

    /**
     * Prices only complete UNPRICED measurements, without changing token counts or the funding source.
     * Frozen charges are immutable; concurrent repricers can price a row only once. The cost and the decision to
     * price were taken from {@code priced}'s measurement: its tokens and its search units, which no price covers. A
     * row a late call remeasured since in any of them is left for the next pass, which reads the new measurement.
     */
    @WorkspaceAgnostic("ID-based price repair; the event id comes from the cross-tenant repricing enumeration")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE llm_usage_event SET cost_usd = :costUsd, pricing_state = :#{#price.pricingState().name()},"
                    + " applied_price_id = :#{#price.appliedPriceId()}, applied_workspace_model_id ="
                    + " :#{#price.appliedWorkspaceModelId()}, applied_per_1m_input_usd = :#{#price.per1mInputUsd()},"
                    + " applied_per_1m_output_usd = :#{#price.per1mOutputUsd()}, applied_per_1m_cache_read_usd ="
                    + " :#{#price.per1mCacheReadUsd()}, applied_per_1m_cache_write_usd = :#{#price.per1mCacheWriteUsd()}"
                    + " WHERE id = :id AND pricing_state = 'UNPRICED' AND measurement_verification = 'VERIFIED' AND"
                    + " unreported_calls = 0 AND (input_tokens > 0 OR output_tokens > 0 OR cache_read_tokens > 0 OR"
                    + " cache_write_tokens > 0) AND input_tokens = :#{#priced.inputTokens()} AND output_tokens ="
                    + " :#{#priced.outputTokens()} AND cache_read_tokens = :#{#priced.cacheReadTokens()} AND"
                    + " cache_write_tokens = :#{#priced.cacheWriteTokens()} AND search_units ="
                    + " :#{#priced.searchUnits()}",
            nativeQuery = true)
    int applyResolvedPrice(
            @Param("id") UUID id,
            @Param("costUsd") BigDecimal costUsd,
            @Param("price") LlmPriceSnapshot price,
            @Param("priced") UnpricedLedgerRow priced);

    /**
     * What turns the instance verdict from WITHIN into UNVERIFIABLE: a row that {@link #sumCost} cannot count, as it
     * has neither a price nor an upper bound ({@link #UNKNOWN}).
     */
    @Query(
            value = "SELECT EXISTS(SELECT 1 FROM llm_usage_event e WHERE e.workspace_id ="
                    + " :workspaceId AND e.occurred_at >= :from AND e.occurred_at < :to AND "
                    + UNKNOWN
                    + " AND e.funding_source = 'INSTANCE')",
            nativeQuery = true)
    boolean existsUnpricedInstanceFunded(
            @Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * The own-provider mirror of {@link #existsUnpricedInstanceFunded}. Kept separate so each cap
     * is only ever blocked by a blind spot its own owner can clear.
     */
    @Query(
            value = "SELECT EXISTS(SELECT 1 FROM llm_usage_event e WHERE e.workspace_id ="
                    + " :workspaceId AND e.occurred_at >= :from AND e.occurred_at < :to AND "
                    + UNKNOWN
                    + " AND e.funding_source = 'WORKSPACE')",
            nativeQuery = true)
    boolean existsUnpricedWorkspaceFunded(
            @Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Any own-provider ledger row in the window, whatever its price. A confirmed $0.00 row counts.
     */
    @Query(
            value = "SELECT EXISTS(SELECT 1 FROM llm_usage_event e WHERE e.workspace_id ="
                    + " :workspaceId AND e.occurred_at >= :from AND e.occurred_at < :to AND"
                    + " e.funding_source = 'WORKSPACE')",
            nativeQuery = true)
    boolean existsWorkspaceFunded(
            @Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            value = "SELECT " + OWN_PROVIDER_CONNECTED + " FROM workspace w WHERE w.id = :workspaceId",
            nativeQuery = true)
    boolean isOwnProviderConnected(@Param("workspaceId") Long workspaceId);

    /**
     * The measured instance-funded cost of a month ({@link #CONFIRMED}), without the bound of any open or unmeasured
     * call: what the budget alert compares with the cap.
     */
    @Query(
            value = "SELECT COALESCE(SUM(" + CONFIRMED + "), 0) FROM llm_usage_event e WHERE"
                    + " e.workspace_id = :workspaceId AND e.occurred_at >= :from AND"
                    + " e.occurred_at < :to AND e.funding_source = 'INSTANCE'",
            nativeQuery = true)
    BigDecimal sumConfirmedCost(
            @Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * The spend of a month in each purse, read in one statement: the confirmed cost ({@link #CONFIRMED}), and the calls
     * that count at their upper bound ({@link #BOUNDED}) with their bounds. Confirmed cost plus the bounds is the sum
     * that the cap compares ({@link #sumCost}, {@link #sumByoCost}), and no call can be in both.
     */
    @Query(
            value = "SELECT COALESCE(SUM(" + CONFIRMED + ") FILTER (WHERE e.funding_source = 'INSTANCE'), 0)"
                    + " AS instanceConfirmedUsd,"
                    + " COUNT(*) FILTER (WHERE " + BOUNDED + " AND e.funding_source = 'INSTANCE') AS instanceCalls,"
                    + " COALESCE(SUM(e.cost_bound_usd) FILTER (WHERE " + BOUNDED
                    + " AND e.funding_source = 'INSTANCE'), 0) AS instanceUpperBoundUsd,"
                    + " COALESCE(SUM(" + CONFIRMED + ") FILTER (WHERE e.funding_source = 'WORKSPACE'), 0)"
                    + " AS ownProviderConfirmedUsd,"
                    + " COUNT(*) FILTER (WHERE " + BOUNDED + " AND e.funding_source = 'WORKSPACE') AS ownProviderCalls,"
                    + " COALESCE(SUM(e.cost_bound_usd) FILTER (WHERE " + BOUNDED
                    + " AND e.funding_source = 'WORKSPACE'), 0) AS ownProviderUpperBoundUsd"
                    + " FROM llm_usage_event e WHERE e.workspace_id = :workspaceId AND e.occurred_at >= :from"
                    + " AND e.occurred_at < :to",
            nativeQuery = true)
    MonthSpend aggregateSpend(
            @Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    interface MonthSpend {
        BigDecimal getInstanceConfirmedUsd();

        long getInstanceCalls();

        BigDecimal getInstanceUpperBoundUsd();

        BigDecimal getOwnProviderConfirmedUsd();

        long getOwnProviderCalls();

        BigDecimal getOwnProviderUpperBoundUsd();
    }

    @Query(
            value = "SELECT e.job_type AS jobType, COALESCE(SUM(e.cost_usd) FILTER (WHERE"
                    + " e.pricing_state = 'PRICED' AND e.funding_source = 'INSTANCE'), 0) AS"
                    + " pricedTotalCostUsd, COALESCE(SUM(e.cost_usd) FILTER (WHERE"
                    + " e.pricing_state = 'PRICED' AND e.funding_source = 'WORKSPACE'), 0) AS"
                    + " byoTotalCostUsd, "
                    + UNPRICED_RUNS
                    + " AS unpricedEventCount, SUM(e.input_tokens) AS inputTokens,"
                    + " SUM(e.output_tokens) AS outputTokens, SUM(e.cache_read_tokens) AS"
                    + " cacheReadTokens, SUM(e.cache_write_tokens) AS cacheWriteTokens,"
                    + " SUM(e.total_calls) AS totalCalls, "
                    + RUNS
                    + " AS events FROM llm_usage_event e WHERE e.workspace_id ="
                    + " :workspaceId AND e.occurred_at >= :from AND e.occurred_at < :to"
                    + " GROUP BY e.job_type ORDER BY pricedTotalCostUsd DESC",
            nativeQuery = true)
    List<JobTypeAggregate> aggregateByJobType(
            @Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            value = "SELECT (e.occurred_at AT TIME ZONE 'UTC')::date AS day,"
                    + " COALESCE(SUM(e.cost_usd) FILTER (WHERE e.pricing_state = 'PRICED' AND"
                    + " e.funding_source = 'INSTANCE'), 0) AS pricedTotalCostUsd,"
                    + " COALESCE(SUM(e.cost_usd) FILTER (WHERE e.pricing_state = 'PRICED' AND"
                    + " e.funding_source = 'WORKSPACE'), 0) AS byoTotalCostUsd, "
                    + UNPRICED_RUNS
                    + " AS unpricedEventCount, "
                    + RUNS
                    + " AS events FROM llm_usage_event e WHERE e.workspace_id ="
                    + " :workspaceId AND e.occurred_at >= :from AND e.occurred_at < :to"
                    + " GROUP BY day ORDER BY day",
            nativeQuery = true)
    List<DailyAggregate> aggregateByDay(
            @Param("workspaceId") Long workspaceId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Instance-admin cross-tenant rollup: every workspace, including those with no spend this
     * window. Unpaged — the row count is the instance's workspace count, and ranking by spend needs
     * the whole month anyway.
     */
    @Query(
            value = "SELECT w.id AS workspaceId, w.slug AS workspaceSlug, w.display_name AS"
                    + " displayName, w.monthly_llm_budget_usd AS monthlyBudgetUsd,"
                    + " w.monthly_byo_llm_budget_usd AS byoMonthlyBudgetUsd,"
                    + " COALESCE(SUM(e.cost_usd) FILTER (WHERE e.pricing_state = 'PRICED' AND"
                    + " e.funding_source = 'INSTANCE'), 0) AS pricedTotalCostUsd,"
                    + " COALESCE(SUM(e.cost_usd) FILTER (WHERE e.pricing_state = 'PRICED' AND"
                    + " e.funding_source = 'WORKSPACE'), 0) AS byoTotalCostUsd,"
                    + " COALESCE(BOOL_OR(" + UNKNOWN + " AND e.funding_source ="
                    + " 'WORKSPACE'), false) AS hasUnpricedByoUsage,"
                    + " COALESCE(bool_or(" + UNKNOWN + " AND e.funding_source ="
                    + " 'INSTANCE'), false) AS hasUnpricedInstanceUsage,"
                    + " COUNT(e.id) FILTER (WHERE " + BOUNDED + " AND e.funding_source = 'INSTANCE')"
                    + " AS instanceUnmeasuredCalls,"
                    + " COALESCE(SUM(e.cost_bound_usd) FILTER (WHERE " + BOUNDED + " AND e.funding_source ="
                    + " 'INSTANCE'), 0) AS instanceUpperBoundUsd,"
                    + " COUNT(e.id) FILTER (WHERE " + BOUNDED + " AND e.funding_source = 'WORKSPACE')"
                    + " AS ownProviderUnmeasuredCalls,"
                    + " COALESCE(SUM(e.cost_bound_usd) FILTER (WHERE " + BOUNDED + " AND e.funding_source ="
                    + " 'WORKSPACE'), 0) AS ownProviderUpperBoundUsd,"
                    + " COALESCE(BOOL_OR(e.funding_source = 'WORKSPACE'), false) AS"
                    + " hasByoUsage, "
                    + OWN_PROVIDER_CONNECTED
                    + " AS ownProviderConnected, "
                    + RUNS
                    + " AS events FROM workspace w LEFT JOIN llm_usage_event e ON"
                    + " e.workspace_id = w.id AND e.occurred_at >= :from AND e.occurred_at"
                    + " < :to GROUP BY w.id, w.slug, w.display_name,"
                    + " w.monthly_llm_budget_usd, w.monthly_byo_llm_budget_usd ORDER BY"
                    + " pricedTotalCostUsd DESC",
            nativeQuery = true)
    List<WorkspaceAggregate> aggregateByWorkspace(@Param("from") Instant from, @Param("to") Instant to);

    interface JobTypeAggregate {
        String getJobType();

        BigDecimal getPricedTotalCostUsd();

        BigDecimal getByoTotalCostUsd();

        long getUnpricedEventCount();

        long getInputTokens();

        long getOutputTokens();

        long getCacheReadTokens();

        long getCacheWriteTokens();

        long getTotalCalls();

        long getEvents();
    }

    interface DailyAggregate {
        LocalDate getDay();

        BigDecimal getPricedTotalCostUsd();

        BigDecimal getByoTotalCostUsd();

        long getUnpricedEventCount();

        long getEvents();
    }

    interface WorkspaceAggregate {
        Long getWorkspaceId();

        String getWorkspaceSlug();

        String getDisplayName();

        @Nullable
        BigDecimal getMonthlyBudgetUsd();

        @Nullable
        BigDecimal getByoMonthlyBudgetUsd();

        BigDecimal getPricedTotalCostUsd();

        BigDecimal getByoTotalCostUsd();

        boolean isHasUnpricedInstanceUsage();

        boolean isHasUnpricedByoUsage();

        long getInstanceUnmeasuredCalls();

        BigDecimal getInstanceUpperBoundUsd();

        long getOwnProviderUnmeasuredCalls();

        BigDecimal getOwnProviderUpperBoundUsd();

        boolean isHasByoUsage();

        boolean isOwnProviderConnected();

        long getEvents();
    }
}
