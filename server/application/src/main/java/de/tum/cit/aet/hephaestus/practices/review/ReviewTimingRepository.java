package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ReviewTimingRepository extends Repository<Review, UUID> {
    @Query(value = """
        SELECT id,created_at AS createdAt FROM review WHERE workspace_id=:workspaceId AND origin<>'TRIAL'
          AND created_at>=:from AND created_at<:to AND (CAST(:afterTime AS timestamptz) IS NULL
            OR (created_at,id::text)>(CAST(:afterTime AS timestamptz),CAST(:afterId AS text)))
        ORDER BY created_at,id::text
        """, nativeQuery = true)
    List<CursorRow> window(
            long workspaceId,
            Instant from,
            Instant to,
            @Nullable Instant afterTime,
            @Nullable UUID afterId,
            Pageable page);

    @Query(
            value = "SELECT COUNT(*) FROM feedback WHERE workspace_id=:workspaceId AND stop_reason='ITEM_MERGED'"
                    + " AND created_at>=:from AND created_at<:to",
            nativeQuery = true)
    long withheldAfterMerge(long workspaceId, Instant from, Instant to);

    @Query(value = """
        SELECT r.id,r.origin,r.created_at AS createdAt,r.completed_at AS completedAt,
               r.public_review_at AS publicCompletedAt,r.status,
               i.id AS itemId,i.kind,i.source_id AS sourceId,i.parent_id AS parentId,i.path,
               CASE WHEN r.origin='EVENT' AND e.found_by IN ('WEBHOOK','SYNC') THEN e.occurred_at END AS eventAt,
               COALESCE(r.origin='EVENT' AND e.name='scm.pull_request.merged',false) AS startedByMerge,
               (SELECT MIN(c.posted_at) FROM feedback f JOIN feedback_copy c
                  ON c.feedback_id=f.id AND c.workspace_id=f.workspace_id
                WHERE f.workspace_id=r.workspace_id AND f.review_id=r.id AND f.destination='ON_WORK'
                  AND NOT EXISTS(SELECT 1 FROM feedback_decision d WHERE d.workspace_id=f.workspace_id
                    AND d.feedback_id=f.id AND d.action='APPROVE')) AS firstPostedAt,
               (SELECT MIN(f.delivered_at) FROM feedback f WHERE f.workspace_id=r.workspace_id
                  AND f.review_id=r.id AND f.destination IN ('PRACTICE_PAGE','CONVERSATION')) AS firstReadAt
        FROM review r JOIN item i ON i.id=r.item_id AND i.workspace_id=r.workspace_id
        LEFT JOIN item_event e ON e.id=r.event_id AND e.workspace_id=r.workspace_id AND e.item_id=r.item_id
        WHERE r.workspace_id=:workspaceId AND r.id IN (:ids) ORDER BY r.id::text
        """, nativeQuery = true)
    List<TimelineRow> timelines(long workspaceId, Collection<UUID> ids);

    interface CursorRow {
        UUID getId();

        Instant getCreatedAt();
    }

    interface TimelineRow {
        UUID getId();

        String getOrigin();

        Instant getCreatedAt();

        @Nullable
        Instant getCompletedAt();

        @Nullable
        Instant getPublicCompletedAt();

        String getStatus();

        long getItemId();

        String getKind();

        @Nullable
        Long getSourceId();

        @Nullable
        Long getParentId();

        @Nullable
        String getPath();

        @Nullable
        Instant getEventAt();

        boolean getStartedByMerge();

        @Nullable
        Instant getFirstPostedAt();

        @Nullable
        Instant getFirstReadAt();
    }
}
