package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanMembershipQueries;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, UUID>, ReviewQueries, ScanMembershipQueries {
    /** The review's pinned producer, NULL until its first seal chooses one. */
    @Query(
            value = "SELECT feedback_producer FROM review WHERE workspace_id = :workspaceId AND id = :reviewId",
            nativeQuery = true)
    Optional<String> findFeedbackProducer(@Param("workspaceId") long workspaceId, @Param("reviewId") UUID reviewId);

    /** Pins the first producer: the column is never written again, and the entity never writes it. */
    @Modifying
    @Query(value = """
        UPDATE review SET feedback_producer = :producer
        WHERE workspace_id = :workspaceId AND id = :reviewId AND feedback_producer IS NULL
        """, nativeQuery = true)
    int pinFeedbackProducer(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("producer") String producer);

    /**
     * Whether a later review of the item, not cancelled and not a trial, takes the practice over from this one: it
     * already judged or reused it, or it is still open with the practice selected. A later review that ended without
     * measuring the practice takes nothing over.
     */
    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM review o JOIN review r ON r.workspace_id=o.workspace_id AND r.item_id=o.item_id
          WHERE o.workspace_id=:workspaceId AND o.id=:reviewId AND r.kind='ITEM' AND r.origin<>'TRIAL' AND r.id<>o.id
            AND r.ordered_at>o.ordered_at AND r.status<>'CANCELLED'
            AND (EXISTS(SELECT 1 FROM review_item n WHERE n.workspace_id=r.workspace_id AND n.review_id=r.id
                   AND n.item_id=r.item_id AND n.practice_id=:practiceId AND n.state IN ('JUDGED','REUSED'))
              OR (r.status IN ('QUEUED','RUNNING') AND EXISTS(SELECT 1 FROM review_practice p
                   WHERE p.workspace_id=r.workspace_id AND p.review_id=r.id AND p.practice_id=:practiceId
                     AND p.status IN ('QUEUED','READY','RUNNING')))))
        """, nativeQuery = true)
    boolean existsNewerReviewTakingOverPractice(long workspaceId, UUID reviewId, long practiceId);

    /** Whether another review of the item at {@code version}, not a trial, is still queued or running. */
    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM review r WHERE r.workspace_id=:workspaceId AND r.item_id=:itemId AND r.kind='ITEM'
          AND r.origin<>'TRIAL' AND r.item_version=:version AND r.id<>:reviewId
          AND r.status IN ('QUEUED','RUNNING'))
        """, nativeQuery = true)
    boolean existsOtherOpenReviewAtVersion(long workspaceId, long itemId, String version, UUID reviewId);

    /** The occasion of the newest event review of the item at {@code version}. */
    @Query(value = """
        SELECT r.event_id FROM review r WHERE r.workspace_id=:workspaceId AND r.item_id=:itemId AND r.kind='ITEM'
          AND r.origin='EVENT' AND r.event_id IS NOT NULL AND r.item_version=:version
        ORDER BY r.ordered_at DESC, r.id DESC LIMIT 1
        """, nativeQuery = true)
    Optional<UUID> newestEventOccasionAtVersion(long workspaceId, long itemId, String version);

    /** The result counts of item reviews, per review and practice: the results each review recorded. */
    @Query(value = """
        SELECT r.review_id AS reviewId,r.practice_id AS practiceId,
          COUNT(*) FILTER(WHERE r.outcome='MET') AS met,
          COUNT(*) FILTER(WHERE r.outcome='NOT_MET') AS notMet,
          COUNT(*) FILTER(WHERE r.outcome='DOES_NOT_APPLY') AS doesNotApply,
          COUNT(*) FILTER(WHERE r.outcome='COULD_NOT_DETERMINE') AS couldNotDetermine
        FROM result r WHERE r.workspace_id=:ws AND r.review_id IN (:ids) GROUP BY r.review_id,r.practice_id
        """, nativeQuery = true)
    List<PracticeResultCounts> itemResultCounts(long ws, Collection<UUID> ids);

    /** The result counts of scans, per review and practice: each result a member of the scan retains, once. */
    @Query(value = """
        SELECT m.review_id AS reviewId,r.practice_id AS practiceId,
          COUNT(DISTINCT r.id) FILTER(WHERE r.outcome='MET') AS met,
          COUNT(DISTINCT r.id) FILTER(WHERE r.outcome='NOT_MET') AS notMet,
          COUNT(DISTINCT r.id) FILTER(WHERE r.outcome='DOES_NOT_APPLY') AS doesNotApply,
          COUNT(DISTINCT r.id) FILTER(WHERE r.outcome='COULD_NOT_DETERMINE') AS couldNotDetermine
        FROM review_item m JOIN result r ON r.id=m.result_id AND r.workspace_id=m.workspace_id
        WHERE m.workspace_id=:ws AND m.review_id IN (:ids) GROUP BY m.review_id,r.practice_id
        """, nativeQuery = true)
    List<PracticeResultCounts> scanResultCounts(long ws, Collection<UUID> ids);

    interface PracticeResultCounts extends ResultCounts {
        UUID getReviewId();

        long getPracticeId();
    }

    /** The feedback counts of each review on the page, per state, in one read. */
    @Query(
            value = "SELECT review_id AS reviewId,state,count(*) AS count FROM feedback WHERE workspace_id=:ws"
                    + " AND review_id IN (:ids) GROUP BY review_id,state",
            nativeQuery = true)
    List<ReviewFeedbackStateCount> feedbackCounts(long ws, Collection<UUID> ids);

    interface ReviewFeedbackStateCount extends FeedbackStateCount {
        UUID getReviewId();
    }

    interface ResultCounts {
        long getMet();

        long getNotMet();

        long getDoesNotApply();

        long getCouldNotDetermine();
    }

    interface FeedbackStateCount {
        FeedbackState getState();

        long getCount();
    }

    @WorkspaceAgnostic("The scan driver discovers active work across workspaces before scoped assignment checks")
    @Query(
            value = "SELECT * FROM review WHERE kind='SCAN' AND status IN ('QUEUED','RUNNING')"
                    + " ORDER BY created_at,id::text",
            nativeQuery = true)
    List<Review> activeScans(Pageable page);

    @WorkspaceAgnostic("Selects workspaces with expired proof; each workspace is erased in its own scoped transaction")
    @Query(value = """
        SELECT DISTINCT workspace_id FROM review WHERE kind='SCAN' AND completed_at<=:cutoff AND
        COALESCE((report->>'expired')::boolean,false)=false UNION SELECT DISTINCT workspace_id
        FROM result WHERE recorded_at<=:cutoff UNION SELECT DISTINCT workspace_id FROM review
        WHERE origin='TRIAL' AND report IS NOT NULL AND completed_at<=:trialCutoff ORDER BY workspace_id
        """, nativeQuery = true)
    List<Long> workspacesWithExpiredProof(Instant cutoff, Instant trialCutoff);

    @Query(value = """
        SELECT id FROM review WHERE workspace_id=:workspaceId AND kind='SCAN' AND completed_at<=:cutoff
        AND COALESCE((report->>'expired')::boolean,false)=false ORDER BY id::text
        """, nativeQuery = true)
    List<UUID> expiredScanIds(long workspaceId, Instant cutoff);

    /**
     * Clears the report of each trial that ended at or before the cutoff: the report is what cites someone's work. The
     * trial's row stays, as every review's does, and job retention removes its jobs and runs.
     */
    @Modifying
    @Query(
            value = "UPDATE review SET report=NULL WHERE workspace_id=:workspaceId AND origin='TRIAL'"
                    + " AND report IS NOT NULL AND completed_at<=:cutoff",
            nativeQuery = true)
    int clearExpiredTrialReports(long workspaceId, Instant cutoff);

    // A call counts its measured cost, or its upper bound while it has no price (agent.usage
    // LlmUsageEventRepository.SPEND); a review with a call of neither has no known cost.
    @Query(value = """
        WITH costs AS (SELECT q.id,
            sum(CASE WHEN u.cost_bound_usd IS NOT NULL AND (u.call_state='OPEN' OR u.pricing_state='UNPRICED')
              THEN u.cost_bound_usd WHEN u.pricing_state<>'UNPRICED' THEN u.cost_usd END) AS cost,
            bool_or(u.pricing_state='UNPRICED' AND u.cost_bound_usd IS NULL) AS unpriced
          FROM review q JOIN item i ON i.id=q.item_id AND i.workspace_id=q.workspace_id
          JOIN llm_usage_event u ON u.review_id=q.id AND u.workspace_id=q.workspace_id
          WHERE q.workspace_id=:workspaceId AND i.kind=:itemKind AND q.status IN ('COMPLETE','PARTIAL')
            AND q.completed_at>=:since GROUP BY q.id)
        SELECT CASE WHEN bool_or(unpriced) THEN NULL ELSE avg(cost) END FROM costs
        """, nativeQuery = true)
    @Nullable
    BigDecimal meanCostPerReviewUsd(long workspaceId, String itemKind, Instant since);

    long countByWorkspaceIdAndCreatedAtGreaterThanEqual(long workspaceId, Instant since);

    long countByWorkspaceIdAndRequestedByIdAndOriginAndCreatedAtGreaterThanEqual(
            long workspaceId, long accountId, ReviewOrigin origin, Instant since);

    boolean existsByWorkspaceIdAndItemIdAndOriginAndCreatedAtGreaterThanEqual(
            long workspaceId, long itemId, ReviewOrigin origin, Instant since);

    boolean existsByWorkspaceIdAndItemIdAndStatusInAndOriginNot(
            long workspaceId, long itemId, Collection<ReviewStatus> statuses, ReviewOrigin origin);

    long countByWorkspaceIdAndOriginAndStatusIn(
            long workspaceId, ReviewOrigin origin, Collection<ReviewStatus> statuses);

    @WorkspaceAgnostic("Hashes the supplied model snapshot without reading tenant rows")
    @Query(value = "SELECT review_model_digest(CAST(:models AS jsonb))", nativeQuery = true)
    String modelDigest(@Param("models") String models);

    Optional<Review> findByIdAndWorkspaceId(UUID id, long workspaceId);

    List<Review> findByWorkspaceIdAndIdIn(long workspaceId, Collection<UUID> ids);

    Optional<Review> findByWorkspaceIdAndRequestKey(long workspaceId, UUID requestKey);

    Optional<Review> findByWorkspaceIdAndEventId(long workspaceId, UUID eventId);

    @Query("""
        SELECT r.eventId FROM Review r
        WHERE r.workspaceId = :workspaceId AND r.itemId = :itemId AND r.eventId IN :eventIds
        """)
    Set<UUID> findCommittedEventIds(long workspaceId, long itemId, Collection<UUID> eventIds);

    boolean existsByWorkspaceIdAndBatchIdAndItemIdAndStatusNot(
            long workspaceId, UUID batchId, long itemId, ReviewStatus status);

    List<Review> findByWorkspaceIdAndBatchIdOrderByCreatedAtAsc(long workspaceId, UUID batchId);

    List<Review> findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(long workspaceId, long itemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Review r WHERE r.id = :id AND r.workspaceId = :workspaceId")
    Optional<Review> lockByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    /** Filters on the stored status of a scan, as its row shows it. */
    @Query(
            value = "SELECT q.* FROM review q WHERE " + SCANS_WHERE + " ORDER BY q.created_at DESC,q.id DESC",
            countQuery = "SELECT count(*) FROM review q WHERE " + SCANS_WHERE,
            nativeQuery = true)
    Page<Review> findScans(
            @Param("kind") String kind,
            @Param("workspaceId") long workspaceId,
            @Param("repositoryItemId") @Nullable Long repositoryItemId,
            @Param("status") @Nullable String status,
            Pageable pageable);

    String SCANS_WHERE = "q.workspace_id=:workspaceId AND q.kind=:kind"
            + " AND (CAST(:repositoryItemId AS bigint) IS NULL OR q.item_id=:repositoryItemId)"
            + " AND (CAST(:status AS varchar) IS NULL OR q.status=:status)";

    Optional<Review> findFirstByWorkspaceIdAndKindAndStatusInOrderByCreatedAtAsc(
            long workspaceId, ReviewKind kind, Collection<ReviewStatus> statuses);

    boolean existsByWorkspaceIdAndKindAndStatusIn(long workspaceId, ReviewKind kind, Collection<ReviewStatus> statuses);
}
