package de.tum.cit.aet.hephaestus.practices.feedback.decision;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilitySql;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedbackDecisionRepository extends JpaRepository<FeedbackDecision, UUID> {

    List<FeedbackDecision> findByWorkspaceIdAndFeedbackIdOrderByDecidedAtDescIdDesc(long workspaceId, UUID feedbackId);

    @Query(value = """
        SELECT * FROM feedback_decision WHERE workspace_id = :workspaceId AND feedback_id = :feedbackId
          AND action IN ('APPROVE','REJECT') ORDER BY decided_at DESC, id::text DESC LIMIT 1
        """, nativeQuery = true)
    Optional<FeedbackDecision> findLatestApproval(
            @Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    @Query(value = """
        SELECT * FROM feedback_decision WHERE workspace_id = :workspaceId AND feedback_id = :feedbackId
          AND action IN ('WITHDRAW','RESTORE') ORDER BY decided_at DESC, id::text DESC LIMIT 1
        """, nativeQuery = true)
    Optional<FeedbackDecision> findLatestVisibility(
            @Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    interface Visibility {
        UUID getFeedbackId();

        FeedbackDecisionAction getAction();

        Instant getDecidedAt();
    }

    @Query(
            value = "SELECT f.id AS feedbackId,d.action AS action,d.decided_at AS decidedAt FROM feedback f "
                    + "JOIN LATERAL (" + FeedbackVisibilitySql.LATEST + ") d ON true "
                    + "WHERE f.workspace_id=:workspaceId AND f.id IN (:feedbackIds)",
            nativeQuery = true)
    List<Visibility> findCurrentVisibilityForFeedbackIds(
            @Param("workspaceId") long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);
}
