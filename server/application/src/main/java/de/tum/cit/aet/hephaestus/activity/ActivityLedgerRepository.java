package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Source facts and conflict-safe writes for bounded activity repair. */
public interface ActivityLedgerRepository extends Repository<ActivityEvent, UUID> {
    String ISSUE_ROWS = """
                SELECT i.id target_id, i.author_id actor_id, v.occurred_at,
                       v.event_type, v.key_type, v.target_type
                FROM issue i CROSS JOIN LATERAL (VALUES
                    (i.created_at,
                     CASE WHEN i.issue_type='PULL_REQUEST' THEN 'PULL_REQUEST_OPENED' ELSE 'ISSUE_CREATED' END,
                     CASE WHEN i.issue_type='PULL_REQUEST' THEN 'pull_request.opened' ELSE 'issue.created' END,
                     CASE WHEN i.issue_type='PULL_REQUEST' THEN 'pull_request' ELSE 'issue' END),
                    (CASE WHEN i.issue_type='PULL_REQUEST' AND i.is_merged THEN i.merged_at ELSE i.closed_at END,
                     CASE WHEN i.issue_type<>'PULL_REQUEST' THEN 'ISSUE_CLOSED'
                          WHEN i.is_merged THEN 'PULL_REQUEST_MERGED' ELSE 'PULL_REQUEST_CLOSED' END,
                     CASE WHEN i.issue_type<>'PULL_REQUEST' THEN 'issue.closed'
                          WHEN i.is_merged THEN 'pull_request.merged' ELSE 'pull_request.closed' END,
                     CASE WHEN i.issue_type='PULL_REQUEST' THEN 'pull_request' ELSE 'issue' END)
                ) v(occurred_at, event_type, key_type, target_type)
                WHERE i.repository_id=:repositoryId AND i.deleted_at IS NULL
                  AND (v.event_type IN ('PULL_REQUEST_OPENED','ISSUE_CREATED')
                       OR i.state='CLOSED' OR i.is_merged)
                """;
    String REVIEW_ROWS = """
                SELECT r.id target_id, r.author_id actor_id, r.submitted_at occurred_at,
                       CASE WHEN r.state='DISMISSED' THEN 'REVIEW_UNKNOWN' ELSE 'REVIEW_' || r.state END event_type,
                       CASE WHEN r.state='DISMISSED' THEN 'review.unknown' ELSE 'review.' || lower(r.state) END key_type,
                       'review' target_type
                FROM pull_request_review r JOIN issue i ON i.id=r.pull_request_id
                WHERE i.repository_id=:repositoryId AND i.deleted_at IS NULL
                  AND r.state IN ('APPROVED','CHANGES_REQUESTED','COMMENTED','DISMISSED','UNKNOWN')
                  AND NOT (
                """ + ActivityEventRepository.REPLY_ONLY_REVIEW + ")";
    String REPLY_REVIEW_ROWS = """
                SELECT r.id target_id, r.author_id actor_id, r.submitted_at occurred_at,
                       'REVIEW_COMMENTED' event_type, 'review.commented' key_type, 'review' target_type
                FROM pull_request_review r JOIN issue i ON i.id=r.pull_request_id
                WHERE i.repository_id=:repositoryId AND (
                """ + ActivityEventRepository.REPLY_ONLY_REVIEW + ")";
    String COMMENT_ROWS = """
                SELECT c.id target_id, c.author_id actor_id, c.created_at occurred_at,
                       'COMMENT_CREATED' event_type, 'comment.created' key_type, 'issue_comment' target_type
                FROM issue_comment c JOIN issue i ON i.id=c.issue_id
                WHERE i.repository_id=:repositoryId AND i.deleted_at IS NULL
                """;
    String REVIEW_COMMENT_ROWS = """
                SELECT c.id target_id, c.author_id actor_id, c.created_at occurred_at,
                       'REVIEW_COMMENT_CREATED' event_type, 'review_comment.created' key_type,
                       'review_comment' target_type
                FROM pull_request_review_comment c JOIN issue i ON i.id=c.pull_request_id
                WHERE i.repository_id=:repositoryId AND i.deleted_at IS NULL
                """;

    String SOURCES = "SELECT 'ISSUE' source_type, s.* FROM (" + ISSUE_ROWS + ") s" + " UNION ALL "
            + "SELECT 'REVIEW' source_type, s.* FROM ("
            + REVIEW_ROWS + ") s" + " UNION ALL " + "SELECT 'REPLY_REVIEW' source_type, s.* FROM ("
            + REPLY_REVIEW_ROWS + ") s" + " UNION ALL " + "SELECT 'COMMENT' source_type, s.* FROM ("
            + COMMENT_ROWS + ") s" + " UNION ALL " + "SELECT 'REVIEW_COMMENT' source_type, s.* FROM ("
            + REVIEW_COMMENT_ROWS + ") s";
    String CANDIDATES = "WITH candidate AS (SELECT * FROM (" + SOURCES + """
            ) source WHERE source_type=:source AND target_id > :after AND target_id <= :last)
            """;

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM repository r JOIN repository_to_monitor m
              ON (m.native_id=r.native_id OR (m.native_id IS NULL AND m.name_with_owner=r.name_with_owner))
            WHERE m.workspace_id=:workspaceId AND r.id=:repositoryId AND r.provider_id=:providerId)
            """, nativeQuery = true)
    boolean isMonitored(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryId") long repositoryId,
            @Param("providerId") long providerId);

    @WorkspaceAgnostic("Provider source rows are shared; repository identity bounds this read")
    @Query(
            value = "SELECT coalesce(max(target_id),0) FROM (" + SOURCES + ") source WHERE source_type=:source",
            nativeQuery = true)
    long findCeiling(@Param("repositoryId") long repositoryId, @Param("source") String source);

    @WorkspaceAgnostic("Provider source rows are shared; repository identity bounds this read")
    @Query(value = "SELECT DISTINCT target_id FROM (" + SOURCES + """
            ) candidate WHERE source_type=:source AND target_id > :after AND target_id <= :ceiling
            ORDER BY target_id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findChunkIds(
            @Param("repositoryId") long repositoryId,
            @Param("source") String source,
            @Param("after") long after,
            @Param("ceiling") long ceiling,
            @Param("limit") int limit);

    @WorkspaceAgnostic("Provider source rows are shared; admitted authors are fenced before workspace writes")
    @Query(
            value = CANDIDATES + "SELECT DISTINCT actor_id FROM candidate WHERE actor_id IS NOT NULL ORDER BY actor_id",
            nativeQuery = true)
    List<Long> findChunkActors(
            @Param("repositoryId") long repositoryId,
            @Param("source") String source,
            @Param("after") long after,
            @Param("last") long last);

    @Modifying
    @Query(value = CANDIDATES + """
            DELETE FROM activity_event e USING candidate c
            WHERE e.workspace_id=:workspaceId AND e.target_type='review'
              AND e.event_type='REVIEW_COMMENTED' AND e.target_id=c.target_id
            """, nativeQuery = true)
    int deleteReplyEvents(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryId") long repositoryId,
            @Param("source") String source,
            @Param("after") long after,
            @Param("last") long last);

    @Modifying
    @Query(value = CANDIDATES + """
            INSERT INTO activity_event
                (id, event_key, event_type, occurred_at, actor_id, workspace_id,
                 repository_id, target_type, target_id, ingested_at)
            SELECT gen_random_uuid(), key_type || ':' || target_id || ':' ||
                   floor(extract(epoch FROM occurred_at) * 1000)::bigint,
                   event_type, occurred_at, actor_id, :workspaceId,
                   :repositoryId, target_type, target_id, CURRENT_TIMESTAMP
            FROM candidate c
            WHERE occurred_at IS NOT NULL AND actor_id IN (:actors)
              AND NOT EXISTS (SELECT 1 FROM person_suppression s JOIN "user" u
                  ON u.provider_id=s.provider_id AND u.native_id::text=s.subject
                  WHERE u.id=c.actor_id AND s.team_key='')
              AND NOT EXISTS (SELECT 1 FROM activity_event e
                  WHERE e.workspace_id=:workspaceId AND e.target_type=c.target_type
                    AND e.target_id=c.target_id AND e.event_type=c.event_type)
            ON CONFLICT (workspace_id, event_key) DO NOTHING
            """, nativeQuery = true)
    int insertChunk(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryId") long repositoryId,
            @Param("source") String source,
            @Param("after") long after,
            @Param("last") long last,
            @Param("actors") List<Long> actors);
}
