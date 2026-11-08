package de.tum.cit.aet.hephaestus.practices.feedback.response;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedbackResponseRepository extends JpaRepository<FeedbackResponse, UUID> {

    @Query(value = """
        SELECT * FROM feedback_response WHERE workspace_id = :workspaceId AND feedback_id = :feedbackId
         ORDER BY created_at DESC, id::text DESC LIMIT 1
        """, nativeQuery = true)
    Optional<FeedbackResponse> findCurrent(
            @Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    @Query(value = """
        SELECT latest.resolution AS resolution, count(*) AS count FROM (
          SELECT DISTINCT ON (r.feedback_id) r.feedback_id, r.resolution
           FROM feedback_response r JOIN feedback f ON f.id = r.feedback_id AND f.workspace_id = r.workspace_id
          WHERE r.workspace_id = :workspaceId AND f.developer_id = :developerId AND f.delivered_at IS NOT NULL
          ORDER BY r.feedback_id, r.created_at DESC, r.id::text DESC
        ) latest WHERE latest.resolution IS NOT NULL GROUP BY latest.resolution
        """, nativeQuery = true)
    List<ResolutionCount> countCurrentResolutions(
            @Param("workspaceId") long workspaceId, @Param("developerId") long developerId);

    interface ResolutionCount {
        FeedbackResolution getResolution();

        long getCount();
    }

    @Query(value = """
        SELECT DISTINCT ON (r.feedback_id) r.feedback_id,
          CASE WHEN r.resolution = 'DISAGREE' THEN r.created_at END AS disagreed_at
         FROM feedback_response r
         WHERE r.workspace_id = :workspaceId AND r.feedback_id IN (:feedbackIds)
         ORDER BY r.feedback_id, r.created_at DESC, r.id::text DESC
        """, nativeQuery = true)
    List<ResponseTime> responseTimes(long workspaceId, Collection<UUID> feedbackIds);

    record ResponseTime(UUID feedbackId, @Nullable Instant disagreedAt) {}

    @Query(value = """
        SELECT DISTINCT ON (r.feedback_id) r.* FROM feedback_response r
         WHERE r.workspace_id = :workspaceId AND r.feedback_id IN (:feedbackIds)
         ORDER BY r.feedback_id, r.created_at DESC, r.id::text DESC
        """, nativeQuery = true)
    List<FeedbackResponse> findCurrentForFeedbackIds(
            @Param("workspaceId") long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);
}
