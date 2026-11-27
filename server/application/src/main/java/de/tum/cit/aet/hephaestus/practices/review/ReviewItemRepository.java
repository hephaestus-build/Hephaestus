package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, ReviewItem.Id> {
    /** The items of one review by state and recorded reason, which only a withheld item has. */
    @Query(value = """
        SELECT state,reason,COUNT(*) AS items FROM review_item WHERE workspace_id=:ws AND review_id=:id
        GROUP BY state,reason
        """, nativeQuery = true)
    List<ItemEndState> endStates(long ws, UUID id);

    interface ItemEndState {
        String getState();

        @Nullable
        String getReason();

        long getItems();
    }

    /** The item counts of each review on the page, per practice, in one read. */
    @Query(value = """
        SELECT review_id AS reviewId,practice_id AS practiceId,COUNT(*) AS listed,
          COUNT(*) FILTER(WHERE state IN ('JUDGED','REUSED')) AS reviewed,
          COUNT(*) FILTER(WHERE state='REUSED') AS reused,COUNT(*) FILTER(WHERE state='WITHHELD') AS withheld,
          COUNT(*) FILTER(WHERE state IN ('LISTED','STAGED')) AS pending FROM review_item
        WHERE workspace_id=:ws AND review_id IN (:ids) GROUP BY review_id,practice_id
        """, nativeQuery = true)
    List<ReviewPracticeCounts> practiceCounts(long ws, Collection<UUID> ids);

    /**
     * Each item review's practices that its attempts work on, how many of them an attempt ended, and how its items
     * stand. An attempt binds a practice's items to its job, and the practice ends when none of them is still listed.
     * A practice decided at creation, such as one withheld for readiness, has no job and is not counted, so a review
     * that has not started ends nothing. A scan lists its items in pages, so its practices are not all known yet, and
     * it has no row here.
     */
    @Query(value = """
        SELECT q.id AS reviewId,
          COUNT(*) FILTER(WHERE p.listed>0 OR p.bound>0) AS practices,
          COUNT(*) FILTER(WHERE p.listed=0 AND p.bound>0) AS ended,
          COALESCE(SUM(p.unsettled),0) AS unsettled,
          COALESCE(SUM(p.checked),0) AS checked
        FROM review q LEFT JOIN (
          SELECT m.review_id,COUNT(*) FILTER(WHERE m.state='LISTED') AS listed,
            COUNT(*) FILTER(WHERE m.job_id IS NOT NULL) AS bound,
            COUNT(*) FILTER(WHERE m.state IN ('LISTED','STAGED')) AS unsettled,
            COUNT(*) FILTER(WHERE m.state IN ('JUDGED','REUSED','TRIED')) AS checked
          FROM review_item m WHERE m.workspace_id=:ws AND m.review_id IN (:ids)
          GROUP BY m.review_id,m.practice_id) p ON p.review_id=q.id
        WHERE q.workspace_id=:ws AND q.id IN (:ids) AND q.kind='ITEM'
        GROUP BY q.id
        """, nativeQuery = true)
    List<ReviewProgressCounts> progress(long ws, Collection<UUID> ids);

    interface ReviewProgressCounts {
        UUID getReviewId();

        long getPractices();

        long getEnded();

        long getUnsettled();

        long getChecked();
    }

    interface ReviewPracticeCounts extends PracticeCounts {
        UUID getReviewId();
    }

    interface PracticeCounts {
        long getPracticeId();

        long getListed();

        long getReviewed();

        long getReused();

        long getWithheld();

        long getPending();
    }

    boolean existsByWorkspaceIdAndReviewIdAndStateInAndResultIdIsNotNull(
            long workspaceId, UUID reviewId, Collection<ReviewItemState> states);

    long countByWorkspaceIdAndReviewIdAndPracticeId(long workspaceId, UUID reviewId, long practiceId);

    @Query(value = """
        SELECT m.practice_id AS practiceId,m.item_id AS itemId FROM review_item m JOIN item i ON i.id=m.item_id
        AND i.workspace_id=m.workspace_id
        LEFT JOIN result r ON r.workspace_id=m.workspace_id AND r.item_id=m.item_id
          AND r.practice_id=m.practice_id AND r.person_account_id IS NULL AND r.ended_at IS NULL
        WHERE m.workspace_id=:workspaceId AND m.review_id=:reviewId
        AND m.state='LISTED' AND (m.job_id IS NULL OR m.job_id<>ALL(CAST(:active AS uuid[])))
        ORDER BY r.recorded_at ASC NULLS FIRST,i.path,m.item_id,m.practice_id
        """, nativeQuery = true)
    List<PendingAssignment> pending(long workspaceId, UUID reviewId, UUID[] active, Pageable page);

    @Query(value = """
        SELECT m.* FROM review_item m WHERE m.workspace_id=:workspaceId AND m.review_id=:reviewId
        AND m.state='LISTED' AND m.job_id IS NULL
        AND NOT COALESCE((m.detail->>'reuseAttempted')::boolean,false)
        ORDER BY m.item_id,m.practice_id
        """, nativeQuery = true)
    List<ReviewItem> pendingReuse(long workspaceId, UUID reviewId, Pageable page);

    @Query(value = """
        SELECT COUNT(*) AS listed,COUNT(*) FILTER(WHERE state='JUDGED') AS judged,
        COUNT(*) FILTER(WHERE state='REUSED') AS reused,COUNT(*) FILTER(WHERE state='WITHHELD') AS withheld
        FROM review_item WHERE workspace_id=:workspaceId AND review_id=:reviewId
        """, nativeQuery = true)
    StateCounts stateCounts(long workspaceId, UUID reviewId);

    interface PendingAssignment {
        long getPracticeId();

        long getItemId();
    }

    interface StateCounts {
        long getListed();

        long getJudged();

        long getReused();

        long getWithheld();
    }

    @Query(value = """
        SELECT m.practice_id FROM review_item m JOIN review r ON r.id=m.review_id AND
        r.workspace_id=m.workspace_id JOIN review_practice p ON p.review_id=m.review_id AND
        p.workspace_id=m.workspace_id AND p.practice_id=m.practice_id
        LEFT JOIN LATERAL (
          SELECT BOOL_OR(v.ended_at IS NULL AND v.item_version=m.item_version
            AND v.practice_version_id=p.practice_version_id) AS has_current,
            MAX(v.recorded_at) AS last_verdict
          FROM result v WHERE v.workspace_id=m.workspace_id AND v.item_id=m.item_id
            AND v.practice_id=m.practice_id
            AND v.person_account_id IS NOT DISTINCT FROM (m.detail->>'developerId')::bigint
        ) history ON true WHERE m.workspace_id=:workspaceId
        AND m.review_id=:reviewId AND m.item_id=:itemId AND m.state='LISTED' AND p.status IN ('QUEUED','READY')
        AND r.status IN ('QUEUED','RUNNING') AND NOT
        r.cancel_requested
        ORDER BY COALESCE(history.has_current,false),history.last_verdict ASC NULLS FIRST,m.practice_id
        """, nativeQuery = true)
    List<Long> executablePracticeIds(long workspaceId, UUID reviewId, long itemId);

    /**
     * The practices that a queued or running review of the item lists and has not admitted yet, with that review. A
     * staged result stays the earlier review's until it seals.
     */
    @Query(value = """
        SELECT m.practice_id AS practiceId, r.id AS reviewId, r.status AS status, r.origin AS origin,
        CAST(r.checks->>'startedAt' AS timestamptz) AS startedAt FROM review_item m
        JOIN review r ON r.id=m.review_id AND r.workspace_id=m.workspace_id WHERE m.workspace_id=:workspaceId
        AND m.item_id=:itemId AND m.state IN ('LISTED','STAGED') AND r.kind='ITEM' AND r.origin<>'TRIAL'
        AND r.status IN ('QUEUED','RUNNING')
        """, nativeQuery = true)
    List<ListedPractice> listedPractices(long workspaceId, long itemId);

    interface ListedPractice {
        long getPracticeId();

        UUID getReviewId();

        String getStatus();

        String getOrigin();

        /** When the review started running; null while it is queued. */
        @Nullable
        Instant getStartedAt();
    }

    /**
     * Practices whose newest review of the item withheld them for one of {@code reasons}. A cancellation, for whatever
     * reason, decides nothing about a practice, so the review before it does.
     */
    @Query(value = """
        SELECT latest.practice_id FROM (
          SELECT DISTINCT ON (m.practice_id) m.practice_id, m.state, m.reason FROM review_item m
          JOIN review r ON r.id=m.review_id AND r.workspace_id=m.workspace_id
          WHERE m.workspace_id=:workspaceId AND m.item_id=:itemId AND r.kind='ITEM' AND r.origin<>'TRIAL'
            AND m.reason IS DISTINCT FROM 'CANCELLED'
          ORDER BY m.practice_id, r.created_at DESC, r.id DESC
        ) latest WHERE latest.state='WITHHELD' AND latest.reason IN (:reasons)
        """, nativeQuery = true)
    List<Long> lastWithheldPracticeIds(long workspaceId, long itemId, Collection<String> reasons);

    /**
     * Practices whose newest review of the item, cancellations and trials aside, is at {@code version} and withheld them for one
     * of {@code reasons}: what that version still owes.
     */
    @Query(value = """
        SELECT latest.practice_id FROM (
          SELECT DISTINCT ON (m.practice_id) m.practice_id, m.state, m.reason, r.item_version FROM review_item m
          JOIN review r ON r.id=m.review_id AND r.workspace_id=m.workspace_id
          WHERE m.workspace_id=:workspaceId AND m.item_id=:itemId AND r.kind='ITEM' AND r.origin<>'TRIAL'
            AND m.reason IS DISTINCT FROM 'CANCELLED'
          ORDER BY m.practice_id, r.created_at DESC, r.id DESC
        ) latest WHERE latest.state='WITHHELD' AND latest.reason IN (:reasons) AND latest.item_version=:version
        """, nativeQuery = true)
    List<Long> owedAtVersion(long workspaceId, long itemId, String version, Collection<String> reasons);

    boolean existsByWorkspaceIdAndReviewIdAndStateIn(
            long workspaceId, UUID reviewId, Collection<ReviewItemState> states);

    List<ReviewItem> findByWorkspaceIdAndReviewIdAndItemIdIn(long workspaceId, UUID reviewId, Collection<Long> itemIds);

    Optional<ReviewItem> findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
            long workspaceId, UUID reviewId, long practiceId, long itemId);

    List<ReviewItem> findByWorkspaceIdAndReviewIdAndPracticeIdAndJobIdOrderByItemIdAsc(
            long workspaceId, UUID reviewId, long practiceId, UUID jobId);

    /** The assignments of a review bound to one job, for a retained job that recorded none of its own. */
    List<ReviewItem> findByWorkspaceIdAndReviewIdAndJobIdOrderByPracticeIdAscItemIdAsc(
            long workspaceId, UUID reviewId, UUID jobId);

    List<ReviewItem> findByWorkspaceIdAndResultIdOrderByReviewIdAscPracticeIdAscItemIdAsc(
            long workspaceId, UUID resultId);

    /** Every assignment of the given reviews, for one read that resolves many results' producers at once. */
    List<ReviewItem> findByWorkspaceIdAndReviewIdIn(long workspaceId, Collection<UUID> reviewIds);

    /**
     * Every assignment of exactly the given (review, item) pairs, paired by position, in one read. Matching the pair, not
     * each id alone, leaves out a review's other items and an item's other reviews.
     */
    @Query(value = """
        SELECT m.* FROM review_item m WHERE m.workspace_id=:workspaceId AND (m.review_id,m.item_id) IN
        (SELECT k.review_id,k.item_id FROM unnest(CAST(:reviewIds AS uuid[]),CAST(:itemIds AS bigint[]))
          AS k(review_id,item_id))
        """, nativeQuery = true)
    List<ReviewItem> findByWorkspaceIdAndReviewedItems(long workspaceId, UUID[] reviewIds, Long[] itemIds);

    /** The assignments bound to the given jobs, from any of their reviews, in one read. */
    List<ReviewItem> findByWorkspaceIdAndJobIdIn(long workspaceId, Collection<UUID> jobIds);

    /** Every assignment that retains one of the given results, from any review, in one read. */
    List<ReviewItem> findByWorkspaceIdAndResultIdInOrderByReviewIdAscPracticeIdAscItemIdAsc(
            long workspaceId, Collection<UUID> resultIds);

    List<ReviewItem> findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(long workspaceId, UUID reviewId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ReviewItem i WHERE i.workspaceId = :workspaceId AND i.reviewId = :reviewId AND i.practiceId"
            + " = :practiceId AND i.itemId = :itemId")
    Optional<ReviewItem> lockAssignment(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("practiceId") long practiceId,
            @Param("itemId") long itemId);

    boolean existsByWorkspaceIdAndReviewIdAndJobIdAndState(
            long workspaceId, UUID reviewId, UUID jobId, ReviewItemState state);
}
