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

    /**
     * When each developer gave the answer that an admin reads: the resolution and, for a disagreement, its explanation.
     * A new rating or private note on the same answer appends a row, so the time is that of the first row of the
     * newest run with the same answer. Otherwise an admin could see when a private rating changed.
     */
    @Query(value = """
        SELECT DISTINCT ON (v.feedback_id) v.feedback_id,
          CASE WHEN v.resolution IS NOT NULL THEN v.created_at END AS answered_at,
          CASE WHEN v.resolution = 'DISAGREE' THEN v.created_at END AS disagreed_at
         FROM (
          SELECT r.id, r.feedback_id, r.created_at, r.resolution,
            CASE WHEN r.resolution = 'DISAGREE' THEN r.comment END AS explanation,
            lag(r.resolution) OVER w AS previous_resolution,
            lag(CASE WHEN r.resolution = 'DISAGREE' THEN r.comment END) OVER w AS previous_explanation,
            row_number() OVER w AS position
           FROM feedback_response r
           WHERE r.workspace_id = :workspaceId AND r.feedback_id IN (:feedbackIds)
           WINDOW w AS (PARTITION BY r.feedback_id ORDER BY r.created_at, r.id::text)
         ) v
         WHERE v.position = 1 OR v.resolution IS DISTINCT FROM v.previous_resolution
            OR v.explanation IS DISTINCT FROM v.previous_explanation
         ORDER BY v.feedback_id, v.created_at DESC, v.id::text DESC
        """, nativeQuery = true)
    List<ResponseTime> responseTimes(long workspaceId, Collection<UUID> feedbackIds);

    record ResponseTime(
            UUID feedbackId,
            @Nullable Instant answeredAt,
            @Nullable Instant disagreedAt) {}

    @Query(value = """
        SELECT DISTINCT ON (r.feedback_id) r.* FROM feedback_response r
         WHERE r.workspace_id = :workspaceId AND r.feedback_id IN (:feedbackIds)
         ORDER BY r.feedback_id, r.created_at DESC, r.id::text DESC
        """, nativeQuery = true)
    List<FeedbackResponse> findCurrentForFeedbackIds(
            @Param("workspaceId") long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);
}
