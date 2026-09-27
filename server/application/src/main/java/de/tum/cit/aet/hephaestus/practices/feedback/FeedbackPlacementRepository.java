package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("FeedbackPlacement is scoped through its parent Feedback's workspace_id, not its own")
public interface FeedbackPlacementRepository extends JpaRepository<FeedbackPlacement, UUID> {
    List<FeedbackPlacement> findByFeedbackId(UUID feedbackId);

    @Modifying
    @Query(value = """
        INSERT INTO feedback_placement (
            id, feedback_id, placement_type, anchor_kind, anchor_path, anchor_start_line,
            anchor_end_line, anchor_side, posted_comment_ref, posted_comment_url, created_at
        ) VALUES (
            :#{#placement.id()}, :#{#placement.feedbackId()}, :#{#placement.placementType()},
            :#{#placement.anchorKind()}, :#{#placement.anchorPath()}, :#{#placement.anchorStartLine()},
            :#{#placement.anchorEndLine()}, :#{#placement.anchorSide()},
            :#{#placement.postedCommentRef()}, :#{#placement.postedCommentUrl()}, CURRENT_TIMESTAMP
        ) ON CONFLICT (feedback_id, posted_comment_ref) DO NOTHING
        """, nativeQuery = true)
    int insertProviderPlacementIfAbsent(@Param("placement") ProviderPlacement placement);

    record ProviderPlacement(
            UUID id,
            UUID feedbackId,
            String placementType,
            @Nullable String anchorKind,
            @Nullable String anchorPath,
            @Nullable Integer anchorStartLine,
            @Nullable Integer anchorEndLine,
            @Nullable String anchorSide,
            String postedCommentRef,
            @Nullable String postedCommentUrl) {}

    @Query("""
        SELECT p FROM FeedbackPlacement p
        WHERE p.feedback.threadKey = :threadKey
          AND p.feedback.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.DELIVERED
          AND p.placementType = de.tum.cit.aet.hephaestus.practices.feedback.PlacementType.SUMMARY
          AND p.postedCommentRef IS NOT NULL
        ORDER BY p.feedback.createdAt DESC
        LIMIT 1
        """)
    Optional<FeedbackPlacement> findLatestDeliveredSummary(@Param("threadKey") String threadKey);

    @Query(value = """
        SELECT fp.*
        FROM feedback_placement fp
        WHERE fp.feedback_id = :feedbackId
        ORDER BY CASE fp.placement_type
                     WHEN 'SUMMARY' THEN 0
                     WHEN 'INLINE' THEN 1
                     WHEN 'CONVERSATION_TURN' THEN 2
                     ELSE 3
                 END,
                 fp.anchor_path NULLS FIRST,
                 fp.anchor_start_line NULLS FIRST,
                 fp.anchor_end_line NULLS FIRST,
                 fp.created_at,
                 fp.id
        """, nativeQuery = true)
    List<FeedbackPlacement> findByFeedbackIdInDisplayOrder(@Param("feedbackId") UUID feedbackId);
}
