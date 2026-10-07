package de.tum.cit.aet.hephaestus.practices.feedback;

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

@Repository
@WorkspaceAgnostic("FeedbackPlacement is scoped through its parent Feedback's workspace_id, not its own")
public interface FeedbackPlacementRepository extends JpaRepository<FeedbackPlacement, UUID> {
    List<FeedbackPlacement> findByFeedbackId(UUID feedbackId);

    /** A comment the provider confirmed without returning its id is recorded once per anchor, like any other. */
    @Modifying
    @Query(value = """
        INSERT INTO feedback_placement (
            id, feedback_id, placement_type, anchor_kind, anchor_path, anchor_start_line,
            anchor_end_line, anchor_side, posted_comment_ref, posted_comment_url, created_at
        )
        SELECT :#{#placement.id()}, :#{#placement.feedbackId()}, :#{#placement.placementType()},
               :#{#placement.anchorKind()}, CAST(:#{#placement.anchorPath()} AS varchar),
               CAST(:#{#placement.anchorStartLine()} AS integer), CAST(:#{#placement.anchorEndLine()} AS integer),
               :#{#placement.anchorSide()}, CAST(:#{#placement.postedCommentRef()} AS varchar),
                CAST(:#{#placement.postedCommentUrl()} AS varchar), CURRENT_TIMESTAMP
        WHERE NOT EXISTS (
            SELECT 1 FROM feedback_placement existing
            WHERE existing.feedback_id = :#{#placement.feedbackId()}
              AND existing.placement_type = :#{#placement.placementType()}
              AND existing.posted_comment_ref IS NULL
              AND CAST(:#{#placement.postedCommentRef()} AS varchar) IS NULL
              AND existing.anchor_path IS NOT DISTINCT FROM CAST(:#{#placement.anchorPath()} AS varchar)
              AND existing.anchor_start_line IS NOT DISTINCT FROM CAST(:#{#placement.anchorStartLine()} AS integer)
              AND existing.anchor_end_line IS NOT DISTINCT FROM CAST(:#{#placement.anchorEndLine()} AS integer))
        ON CONFLICT (feedback_id, posted_comment_ref) DO NOTHING
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
            @Nullable String postedCommentRef,
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

    /**
     * The comments Hephaestus posted on a provider for in-context feedback citing this observation, including one the
     * provider confirmed without an id, leaving out a placement whose comment a later placement took over (an issue
     * summary edited in place by a newer review), since its text is no longer what the provider shows.
     */
    @Query(value = """
        SELECT pl.posted_comment_ref AS "commentRef", pl.placement_type AS "placementType",
               f.id AS "feedbackId", f.agent_job_id AS "agentJobId", f.body AS "body",
               EXISTS (SELECT 1 FROM feedback_approval fa
                       WHERE fa.feedback_id = f.id AND fa.workspace_id = f.workspace_id) AS "approved"
        FROM feedback_observation fo
        JOIN feedback f ON f.id = fo.feedback_id
        JOIN feedback_placement pl ON pl.feedback_id = f.id
        WHERE fo.observation_id = :observationId AND f.workspace_id = :workspaceId
          AND f.channel = 'IN_CONTEXT'
          AND NOT EXISTS (
              SELECT 1 FROM feedback_placement newer
              JOIN feedback newer_feedback ON newer_feedback.id = newer.feedback_id
              WHERE newer.posted_comment_ref = pl.posted_comment_ref
                AND newer_feedback.workspace_id = f.workspace_id
                AND newer.created_at > pl.created_at)
        """, nativeQuery = true)
    List<PostedCopy> findPostedCopies(
            @Param("workspaceId") Long workspaceId, @Param("observationId") UUID observationId);

    interface PostedCopy {
        @Nullable
        String getCommentRef();

        PlacementType getPlacementType();

        UUID getFeedbackId();

        UUID getAgentJobId();

        @Nullable
        String getBody();

        Boolean getApproved();
    }

    /**
     * The provider permalinks of the comments that still show each of these pieces of delivered in-context feedback
     * to this developer, summary first, then line notes in file order. A placement recorded without a permalink (or
     * with a blank one), or
     * whose comment a later placement took over, has no row, since no link to it would show this feedback.
     */
    @Query(value = """
        SELECT pl.feedback_id AS "feedbackId", pl.posted_comment_url AS "url"
        FROM feedback_placement pl
        JOIN feedback f ON f.id = pl.feedback_id
        WHERE pl.feedback_id IN (:feedbackIds) AND f.workspace_id = :workspaceId
          AND f.recipient_user_id = :recipientUserId AND f.channel = 'IN_CONTEXT'
          AND f.delivery_state = 'DELIVERED' AND NULLIF(btrim(pl.posted_comment_url), '') IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM feedback_placement newer
              JOIN feedback newer_feedback ON newer_feedback.id = newer.feedback_id
              WHERE newer.posted_comment_ref = pl.posted_comment_ref
                AND newer_feedback.workspace_id = f.workspace_id
                AND newer.created_at > pl.created_at)
        ORDER BY pl.feedback_id,
                 CASE pl.placement_type WHEN 'SUMMARY' THEN 0 ELSE 1 END,
                 pl.anchor_path NULLS FIRST,
                 pl.anchor_start_line NULLS FIRST,
                 pl.anchor_end_line NULLS FIRST,
                 pl.created_at,
                 pl.id
        """, nativeQuery = true)
    List<PostedCommentUrl> findDeliveredCommentUrls(
            @Param("workspaceId") Long workspaceId,
            @Param("recipientUserId") Long recipientUserId,
            @Param("feedbackIds") Collection<UUID> feedbackIds);

    interface PostedCommentUrl {
        UUID getFeedbackId();

        String getUrl();
    }

    /** The practices behind this feedback whose observations a workspace admin currently holds invalidated. */
    @Query(value = """
        SELECT DISTINCT p.name
        FROM feedback_observation fo
        JOIN observation o ON o.id = fo.observation_id
        JOIN practice p ON p.id = o.practice_id
        JOIN observation_invalidation oi
          ON oi.observation_id = o.id AND oi.workspace_id = o.workspace_id AND oi.restored_at IS NULL
        WHERE fo.feedback_id = :feedbackId AND o.workspace_id = :workspaceId
        ORDER BY p.name
        """, nativeQuery = true)
    List<String> findInvalidatedPracticeNames(
            @Param("workspaceId") Long workspaceId, @Param("feedbackId") UUID feedbackId);

    @Query(value = """
        SELECT fp.*
        FROM feedback_placement fp
        WHERE fp.feedback_id = :feedbackId
        ORDER BY CASE fp.placement_type
                     WHEN 'SUMMARY' THEN 0
                     WHEN 'INLINE' THEN 1
                     WHEN 'LOCATION_COMMENT' THEN 1
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
