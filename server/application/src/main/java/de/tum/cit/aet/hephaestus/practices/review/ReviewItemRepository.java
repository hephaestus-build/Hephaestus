package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, ReviewItem.Id> {
    @Query(value = """
        SELECT practice_id AS practiceId,COUNT(*) AS listed,
          COUNT(*) FILTER(WHERE state IN ('JUDGED','REUSED')) AS reviewed,
          COUNT(*) FILTER(WHERE state='REUSED') AS reused,COUNT(*) FILTER(WHERE state='WITHHELD') AS withheld,
          COUNT(*) FILTER(WHERE state='LISTED') AS pending FROM review_item
        WHERE workspace_id=:ws AND review_id=:id GROUP BY practice_id
        """, nativeQuery = true)
    List<PracticeCounts> practiceCounts(long ws, UUID id);

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
          AND r.practice_id=m.practice_id AND r.developer_id IS NULL AND r.ended_at IS NULL
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
        p.workspace_id=m.workspace_id AND p.practice_id=m.practice_id WHERE m.workspace_id=:workspaceId
        AND m.review_id=:reviewId AND m.item_id=:itemId AND m.state='LISTED' AND p.status='READY'
        AND r.status IN ('QUEUED','RUNNING') AND NOT
        COALESCE((r.checks->>'cancellationRequested')::boolean,false) ORDER BY m.practice_id
        """, nativeQuery = true)
    List<Long> executablePracticeIds(long workspaceId, UUID reviewId, long itemId);

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

    List<ReviewItem> findByWorkspaceIdAndReviewIdAndStateOrderByPracticeIdAscItemIdAsc(
            long workspaceId, UUID reviewId, ReviewItemState state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ReviewItem i WHERE i.workspaceId = :workspaceId AND i.reviewId = :reviewId AND i.practiceId"
            + " = :practiceId AND i.itemId = :itemId")
    Optional<ReviewItem> lockAssignment(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("practiceId") long practiceId,
            @Param("itemId") long itemId);

    long countByWorkspaceIdAndReviewIdAndState(long workspaceId, UUID reviewId, ReviewItemState state);
}
