package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository.BOUNDED;
import static de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository.SPEND;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageAttempt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@ConditionalOnCaptureRole
@Transactional(readOnly = true)
public interface ReviewRuntimeRepository extends Repository<ReviewJob, UUID> {
    // Runtime reads must not hydrate the capture, input, config snapshot, or output JSON.
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot(
          j.id,j.status,j.attempt,j.processingStatus,j.availableAt,j.startedAt,j.completedAt,
          j.exitCode,j.errorMessage,j.traceId)
        FROM ReviewJob j WHERE j.workspaceId=:workspaceId AND j.reviewId=:reviewId
        ORDER BY j.availableAt,j.id
        """)
    List<ReviewJobSnapshot> snapshots(@Param("workspaceId") long workspaceId, @Param("reviewId") UUID reviewId);

    @Query(value = """
        SELECT id FROM review_job WHERE workspace_id=:workspaceId AND review_id=:reviewId
          AND status NOT IN ('QUEUED','RUNNING') AND processing_status IN ('NONE','PENDING')
        """, nativeQuery = true)
    Set<UUID> unsettledJobIds(@Param("workspaceId") long workspaceId, @Param("reviewId") UUID reviewId);

    default List<ReviewUsageAttempt> usage(long workspaceId, UUID reviewId) {
        return usageByReview(workspaceId, Set.of(reviewId)).getOrDefault(reviewId, List.of());
    }

    default Map<UUID, List<ReviewUsageAttempt>> usageByReview(long workspaceId, Set<UUID> reviewIds) {
        if (reviewIds.isEmpty()) return Map.of();
        return Map.copyOf(usageRows(workspaceId, reviewIds.toArray(UUID[]::new)).stream()
                .collect(Collectors.groupingBy(
                        UsageRow::reviewId,
                        Collectors.mapping(ReviewRuntimeRepository::usageAttempt, Collectors.toUnmodifiableList()))));
    }

    private static ReviewUsageAttempt usageAttempt(UsageRow row) {
        return new ReviewUsageAttempt(
                row.sourceId(),
                row.sourceAttempt(),
                row.calls(),
                row.inputTokens(),
                row.outputTokens(),
                row.reasoningTokens(),
                row.cacheReadTokens(),
                row.cacheWriteTokens(),
                row.costUsd(),
                UsagePricingState.valueOf(row.pricingState()),
                row.includesUpperBound());
    }

    /**
     * Each attempt's calls. Its cost counts each open or unmeasured call at its upper bound ({@code
     * LlmUsageEventRepository.SPEND}); it is unknown while one call has neither a price nor a bound.
     */
    @Query(
            value = "SELECT e.review_id, e.source_id, e.source_attempt, CAST(SUM(e.total_calls) AS bigint),"
                    + " CAST(SUM(e.input_tokens) AS bigint), CAST(SUM(e.output_tokens) AS bigint),"
                    + " CAST(SUM(e.reasoning_tokens) AS bigint), CAST(SUM(e.cache_read_tokens) AS bigint),"
                    + " CAST(SUM(e.cache_write_tokens) AS bigint),"
                    + " CASE WHEN bool_and(" + SPEND + " IS NOT NULL) THEN SUM(" + SPEND + ") END,"
                    + " CASE WHEN bool_or(" + SPEND + " IS NULL) THEN 'UNPRICED'"
                    + " WHEN bool_and(e.pricing_state = 'NO_CHARGE' OR (" + BOUNDED + " AND e.cost_bound_usd = 0))"
                    + " THEN 'NO_CHARGE' ELSE 'PRICED' END,"
                    + " bool_or(" + BOUNDED + ")"
                    + " FROM llm_usage_event e WHERE e.workspace_id = :workspaceId AND e.review_id = ANY(:reviewIds)"
                    + " GROUP BY e.review_id, e.source_id, e.source_attempt"
                    + " ORDER BY e.review_id, e.source_id, e.source_attempt",
            nativeQuery = true)
    List<UsageRow> usageRows(@Param("workspaceId") long workspaceId, @Param("reviewIds") UUID[] reviewIds);

    default QueueCounts queueCounts(long workspaceId, Instant asOf) {
        return queueCountsAt(workspaceId, asOf, asOf.minusSeconds(3600), asOf.minusSeconds(86400));
    }

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository$QueueCounts(
          COALESCE(SUM(CASE WHEN j.status='RUNNING' THEN 1L ELSE 0L END),0L),
          COALESCE(SUM(CASE WHEN j.status='QUEUED' AND (j.holdReason IS NULL OR j.holdReason<>'BUDGET') THEN 1L ELSE 0L END),0L),
          MIN(CASE WHEN j.status='QUEUED' AND (j.holdReason IS NULL OR j.holdReason<>'BUDGET') THEN j.availableAt ELSE null END),
          COALESCE(SUM(CASE WHEN j.status='QUEUED' AND j.holdReason='BUDGET' THEN 1L ELSE 0L END),0L),
          COALESCE(SUM(CASE WHEN j.completedAt>=:hour AND j.completedAt<=:asOf THEN 1L ELSE 0L END),0L),
          COALESCE(SUM(CASE WHEN j.completedAt>=:day AND j.completedAt<=:asOf THEN 1L ELSE 0L END),0L))
        FROM ReviewJob j WHERE j.workspaceId=:workspaceId
        """)
    QueueCounts queueCountsAt(
            @Param("workspaceId") long workspaceId,
            @Param("asOf") Instant asOf,
            @Param("hour") Instant hour,
            @Param("day") Instant day);

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository$TimingJob(
          j.reviewId,j.status,j.attempt,j.availableAt,j.startedAt,j.executionStartedAt,j.completedAt,j.holdReason)
        FROM ReviewJob j WHERE j.workspaceId=:workspaceId AND j.reviewId IN :reviewIds
        ORDER BY j.availableAt,j.id
        """)
    List<TimingJob> timingJobs(@Param("workspaceId") long workspaceId, @Param("reviewIds") List<UUID> reviewIds);

    /**
     * What a review's calls cost, each open or unmeasured call at its upper bound ({@code
     * LlmUsageEventRepository.SPEND}); not priced while one call has neither a price nor a bound.
     */
    @Query(
            value = "SELECT COALESCE(SUM(" + SPEND + "), 0), COALESCE(bool_and(" + SPEND + " IS NOT NULL), true)"
                    + " FROM llm_usage_event e WHERE e.workspace_id = :workspaceId AND e.review_id = :reviewId",
            nativeQuery = true)
    Spend spent(@Param("workspaceId") long workspaceId, @Param("reviewId") UUID reviewId);

    /** Each review's jobs counted by status, for many reviews in one read. */
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository$ReviewJobStateCount(
          j.reviewId,j.status,COUNT(j))
        FROM ReviewJob j WHERE j.workspaceId=:workspaceId AND j.reviewId IN :reviewIds GROUP BY j.reviewId,j.status
        """)
    List<ReviewJobStateCount> jobCounts(
            @Param("workspaceId") long workspaceId, @Param("reviewIds") Collection<UUID> reviewIds);

    record ReviewJobStateCount(UUID reviewId, ReviewJobStatus status, long count) {}

    /** The jobs of the given reviews, each with its review, in one read. */
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository$JobOfReview(j.id,j.reviewId)
        FROM ReviewJob j WHERE j.workspaceId=:workspaceId AND j.reviewId IN :reviewIds
        """)
    List<JobOfReview> jobsOf(@Param("workspaceId") long workspaceId, @Param("reviewIds") Collection<UUID> reviewIds);

    record JobOfReview(UUID jobId, UUID reviewId) {}

    record UsageRow(
            UUID reviewId,
            UUID sourceId,
            int sourceAttempt,
            long calls,
            long inputTokens,
            long outputTokens,
            long reasoningTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            @Nullable BigDecimal costUsd,
            String pricingState,
            boolean includesUpperBound) {}

    record TimingJob(
            UUID reviewId,
            ReviewJobStatus status,
            int attempt,
            Instant availableAt,
            @Nullable Instant startedAt,
            @Nullable Instant executionStartedAt,
            @Nullable Instant completedAt,
            @Nullable String holdReason) {}

    record QueueCounts(long running, long waiting, @Nullable Instant oldest, long held, long hour, long day) {}

    record Spend(BigDecimal cost, boolean priced) {}
}
