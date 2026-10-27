package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResultRepository extends JpaRepository<Result, UUID>, ResultQueries {
    // Partly delivered feedback counts: the developer saw the copy that appeared, as their own pages show it.
    @Query(value = """
        SELECT DISTINCT ON (b.result_id) b.result_id AS resultId,f.body FROM feedback_basis b JOIN feedback f
          ON f.id=b.feedback_id AND f.workspace_id=b.workspace_id
        WHERE b.workspace_id=:workspaceId AND b.result_id IN (:ids)
          AND (f.state='DELIVERED' OR (f.state IN ('FAILED','UNCONFIRMED') AND f.delivered_at IS NOT NULL))
          AND f.destination IN ('ON_WORK','CONVERSATION') AND f.body IS NOT NULL
          AND NOT COALESCE((SELECT d.action='WITHDRAW' FROM feedback_decision d WHERE d.workspace_id=f.workspace_id AND d.feedback_id=f.id AND d.action IN ('WITHDRAW','RESTORE') ORDER BY d.decided_at DESC,d.id::text DESC LIMIT 1),false)
        ORDER BY b.result_id,f.delivered_at DESC,f.id::text DESC
        """, nativeQuery = true)
    List<DeliveredGuidance> deliveredGuidance(long workspaceId, Collection<UUID> ids);

    interface DeliveredGuidance {
        UUID getResultId();

        String getBody();
    }

    @Query(value = """
        SELECT r.id FROM result r WHERE r.workspace_id=:workspaceId AND r.ended_at<=:cutoff
        AND NOT EXISTS(SELECT 1 FROM feedback_basis b WHERE b.workspace_id=r.workspace_id AND b.result_id=r.id)
        AND NOT EXISTS(SELECT 1 FROM review_item i JOIN review v ON v.id=i.review_id AND v.workspace_id=i.workspace_id WHERE i.workspace_id=r.workspace_id AND i.result_id=r.id AND v.kind='SCAN' AND (v.completed_at IS NULL OR v.completed_at>:cutoff))
        AND NOT EXISTS(SELECT 1 FROM review_item member JOIN review scan ON scan.id=member.review_id AND scan.workspace_id=member.workspace_id CROSS JOIN LATERAL jsonb_array_elements(COALESCE(member.detail->'problemComparison','[]'::jsonb)) comparison JOIN problem prior ON (prior.id=(comparison->>'problemId')::uuid OR prior.id=(comparison->>'previousProblemId')::uuid) AND prior.workspace_id=member.workspace_id WHERE prior.result_id=r.id AND prior.workspace_id=r.workspace_id AND scan.kind='SCAN' AND (scan.completed_at IS NULL OR scan.completed_at>:cutoff))
        ORDER BY r.id::text
        """, nativeQuery = true)
    List<UUID> expiredUnreferencedIds(long workspaceId, Instant cutoff);

    @Modifying
    @Query(value = """
        DELETE FROM result r WHERE r.workspace_id=:workspaceId AND r.id=ANY(CAST(:ids AS uuid[])) AND NOT EXISTS(
        SELECT 1 FROM feedback_basis b WHERE b.workspace_id=r.workspace_id AND b.result_id=r.id)
        """, nativeQuery = true)
    int deleteWithoutFeedbackBasis(long workspaceId, UUID[] ids);

    Optional<Result> findFirstByWorkspaceIdAndDeveloperIdOrderByRecordedAtDescIdDesc(
            long workspaceId, long developerId);

    Optional<Result> findByIdAndWorkspaceId(UUID id, long workspaceId);

    Optional<Result> findByIdAndDeveloperIdAndWorkspaceId(UUID id, long developerId, long workspaceId);

    List<Result> findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(long workspaceId, UUID reviewId);

    List<Result> findByWorkspaceIdAndItemIdOrderByRecordedAtDescIdDesc(long workspaceId, long itemId);

    List<Result> findByWorkspaceIdAndItemIdAndEndedAtIsNull(long workspaceId, long itemId);

    List<Result> findByWorkspaceIdAndItemIdInAndEndedAtIsNullAndDeveloperIdIsNull(
            long workspaceId, Collection<Long> itemIds);

    List<Result> findByWorkspaceIdAndIdIn(long workspaceId, Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Result r WHERE r.id = :id AND r.workspaceId = :workspaceId")
    Optional<Result> lockByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") long workspaceId);

    @Query("""
        SELECT r FROM Result r
        WHERE r.workspaceId = :workspaceId AND r.practiceId = :practiceId
          AND r.itemId = :itemId AND r.endedAt IS NULL
          AND (r.developerId = :developerId OR (r.developerId IS NULL AND :developerId IS NULL))
        """)
    Optional<Result> findCurrentClaim(
            @Param("workspaceId") long workspaceId,
            @Param("practiceId") long practiceId,
            @Param("itemId") long itemId,
            @Param("developerId") @Nullable Long developerId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Result r SET r.endedAt = CASE WHEN r.recordedAt > :at THEN r.recordedAt ELSE :at END,
            r.endReason = :reason
        WHERE r.workspaceId = :workspaceId AND r.itemId = :itemId AND r.endedAt IS NULL
          AND EXISTS (SELECT 1 FROM Review source WHERE source.id = r.reviewId
              AND source.workspaceId = r.workspaceId AND source.orderedAt <= :at)
        """)
    int endCurrent(
            @Param("workspaceId") long workspaceId,
            @Param("itemId") long itemId,
            @Param("reason") ResultEndReason reason,
            @Param("at") Instant at);

    @Query(value = """
        SELECT r.* FROM result r
        JOIN review_practice prior_method ON prior_method.review_id = r.review_id
          AND prior_method.practice_id = r.practice_id AND prior_method.workspace_id = r.workspace_id
        JOIN review_practice requested_method ON requested_method.review_id = :reviewId
          AND requested_method.practice_id = r.practice_id AND requested_method.workspace_id = r.workspace_id
        WHERE r.workspace_id = :workspaceId AND r.practice_id = :practiceId AND r.item_id = :itemId
          AND r.practice_version_id = :practiceVersionId AND r.item_version = :itemVersion
          AND r.developer_id IS NOT DISTINCT FROM :developerId
          AND r.evidence_version = 1 AND r.ended_at IS NULL
          AND r.summary IS NOT NULL AND r.explanation IS NOT NULL AND r.evidence IS NOT NULL AND r.coverage IS NOT NULL
          AND prior_method.method IS NOT NULL
          AND prior_method.method->>'modelDigest' = requested_method.method->>'modelDigest'
          AND prior_method.method->>'epoch' = requested_method.method->>'epoch'
          AND NOT EXISTS (SELECT 1 FROM result_mark m WHERE m.result_id = r.id
            AND m.workspace_id = r.workspace_id AND m.kind = 'INCORRECT' AND m.removed_at IS NULL)
        """, nativeQuery = true)
    Optional<Result> findReuseCandidate(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("practiceId") long practiceId,
            @Param("practiceVersionId") long practiceVersionId,
            @Param("itemId") long itemId,
            @Param("itemVersion") String itemVersion,
            @Param("developerId") @Nullable Long developerId);
}
