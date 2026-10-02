package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Metadata projections deliberately never select the feedback body or another delivery channel. */
@WorkspaceAgnostic(
        "Every feedback read carries its raw workspace key; mirrored comments are joined to that workspace's monitor")
interface DeliveredWorkFeedbackRepository extends Repository<Feedback, UUID> {
    @Query("""
        SELECT f.id AS id, f.deliveredAt AS deliveredAt FROM Feedback f
        WHERE f.workspaceId = :workspaceId AND f.recipientUserId IN :recipientIds
          AND f.artifactKind = :kind AND f.artifactId = :artifactId
          AND f.channel = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel.IN_CONTEXT
          AND f.deliveryState IN (
              de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.DELIVERED,
              de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.PARTIALLY_DELIVERED,
              de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.PARTIALLY_FAILED,
              de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.FAILED)
          AND EXISTS (SELECT p.id FROM FeedbackPlacement p WHERE p.feedback = f
              AND p.placementType IN (de.tum.cit.aet.hephaestus.practices.feedback.PlacementType.SUMMARY,
                                     de.tum.cit.aet.hephaestus.practices.feedback.PlacementType.INLINE)
              AND p.postedCommentRef IS NOT NULL AND LENGTH(TRIM(p.postedCommentRef)) > 0)
        ORDER BY f.deliveredAt DESC NULLS LAST, f.createdAt DESC, f.id DESC
        """)
    List<DeliveredRow> findDelivered(
            @Param("workspaceId") long workspaceId,
            @Param("recipientIds") Collection<Long> recipientIds,
            @Param("kind") ArtifactKind kind,
            @Param("artifactId") long artifactId,
            Pageable pageable);

    interface DeliveredRow {
        UUID getId();

        @Nullable
        Instant getDeliveredAt();
    }

    @Query("""
        SELECT DISTINCT fo.feedback.id AS feedbackId, fo.observation.practice.slug AS slug, fo.observation.practice.name AS name
        FROM FeedbackObservation fo
        WHERE fo.feedback.workspaceId = :workspaceId AND fo.observation.workspaceId = :workspaceId
          AND fo.feedback.id IN :feedbackIds
          AND fo.feedback.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.DELIVERED
        ORDER BY fo.observation.practice.slug
        """)
    List<PracticeRow> findPractices(
            @Param("workspaceId") long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);

    interface PracticeRow {
        UUID getFeedbackId();

        String getSlug();

        String getName();
    }

    @Query("""
        SELECT p FROM FeedbackPlacement p
        WHERE p.feedback.workspaceId = :workspaceId AND p.feedback.id IN :feedbackIds
          AND p.postedCommentRef IS NOT NULL AND LENGTH(TRIM(p.postedCommentRef)) > 0
          AND p.placementType IN (de.tum.cit.aet.hephaestus.practices.feedback.PlacementType.SUMMARY,
                                 de.tum.cit.aet.hephaestus.practices.feedback.PlacementType.INLINE)
        ORDER BY p.placementType DESC, p.anchorPath, p.anchorStartLine, p.createdAt, p.id
        """)
    List<FeedbackPlacement> findPlacements(
            @Param("workspaceId") long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);

    @Query("""
        SELECT DISTINCT c.nativeId AS nativeId, c.htmlUrl AS url FROM IssueComment c
        JOIN RepositoryToMonitor monitor ON monitor.nameWithOwner = c.issue.repository.nameWithOwner
        WHERE monitor.workspace.id = :workspaceId AND c.issue.id = :artifactId
          AND c.provider = c.issue.provider AND c.nativeId IN :nativeIds
        """)
    List<CommentRow> findSummaryLinks(
            @Param("workspaceId") long workspaceId,
            @Param("artifactId") long artifactId,
            @Param("nativeIds") Collection<Long> nativeIds);

    @Query("""
        SELECT DISTINCT c.nativeId AS nativeId, c.htmlUrl AS url FROM PullRequestReviewComment c
        JOIN RepositoryToMonitor monitor ON monitor.nameWithOwner = c.pullRequest.repository.nameWithOwner
        WHERE monitor.workspace.id = :workspaceId AND c.pullRequest.id = :artifactId
          AND c.provider = c.pullRequest.provider AND c.nativeId IN :nativeIds
        """)
    List<CommentRow> findInlineLinks(
            @Param("workspaceId") long workspaceId,
            @Param("artifactId") long artifactId,
            @Param("nativeIds") Collection<Long> nativeIds);

    interface CommentRow {
        Long getNativeId();

        String getUrl();
    }
}
