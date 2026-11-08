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
    /** One queue row identifies the email without copying it into a maintenance event. */
    String CORRECTED_COMMITS = """
        SELECT gc.id, gc.repository_id, gc.author_id
        FROM commit_identity_resolution q
        JOIN git_commit representative ON representative.id = q.commit_id
        LEFT JOIN commit_contributor contributor ON contributor.id = q.contributor_id
        JOIN git_commit gc ON gc.repository_id = q.repository_id
          AND gc.author_email = CASE q.source WHEN 'AUTHOR' THEN representative.author_email
                                              WHEN 'COMMITTER' THEN representative.committer_email
                                              ELSE contributor.email END
        WHERE q.id = :resolutionId
        """;

    @WorkspaceAgnostic("One provider answer bounds shared commit rows before activity write admission")
    @Query(
            value = "SELECT DISTINCT source.author_id FROM (" + CORRECTED_COMMITS
                    + ") source WHERE source.author_id IS NOT NULL ORDER BY source.author_id",
            nativeQuery = true)
    List<Long> findCorrectedCommitAuthors(@Param("resolutionId") long resolutionId);

    @WorkspaceAgnostic(
            "One provider answer corrects derived commit actors in each workspace that holds the source event")
    @Modifying
    @Query(
            value = "UPDATE activity_event e SET actor_id = source.author_id FROM (" + CORRECTED_COMMITS + """
        ) source
        WHERE e.repository_id = source.repository_id AND e.target_id = source.id
          AND e.target_type = 'commit' AND e.event_type = 'COMMIT_CREATED'
          AND e.actor_id IS DISTINCT FROM source.author_id
          AND (source.author_id IS NULL OR source.author_id IN (:admitted))
          AND NOT EXISTS (SELECT 1 FROM identity_restriction s JOIN "user" u
              ON u.provider_id = s.provider_id AND s.kind = 'ERASED'
                AND u.native_id::text = s.provider_user_key AND s.team_key = ''
              WHERE u.id = source.author_id)
        """,
            nativeQuery = true)
    int correctCommitActors(@Param("resolutionId") long resolutionId, @Param("admitted") List<Long> admitted);

    /**
     * Issue row {@code i} is contradicted by a pull request: GitHub numbers issues and pull requests in one
     * space per repository, so a stored GitHub pull request with its provider, repository and number means
     * {@code i} is not an issue. Native ids are not compared: an issue payload's id is not the pull
     * request's. GitLab numbers issues and merge requests separately, so it is never a witness there.
     */
    String PULL_REQUEST_SIBLING = """
                EXISTS (SELECT 1 FROM issue p JOIN identity_provider ip ON ip.id=p.provider_id
                    WHERE p.issue_type='PULL_REQUEST' AND ip.type='GITHUB' AND p.provider_id=i.provider_id
                      AND p.repository_id=i.repository_id AND p.number=i.number)
                """;
    /**
     * Targets whose issue lifecycle facts are invalid: a row that is a pull request now, and an issue row a
     * pull request contradicts. Tombstoned rows are included, since their recorded facts outlive them.
     */
    String INVALID_ISSUE_ROWS = """
                SELECT i.id parent_id, i.id target_id, i.author_id actor_id, i.created_at occurred_at,
                       'ISSUE_CREATED' event_type, 'issue.created' key_type, 'issue' target_type
                FROM issue i
                WHERE i.repository_id=:repositoryId
                  AND (i.issue_type='PULL_REQUEST' OR (i.issue_type<>'PULL_REQUEST' AND
                """ + PULL_REQUEST_SIBLING + "))";

    String ISSUE_ROWS = """
                SELECT i.id parent_id, i.id target_id, i.author_id actor_id, v.occurred_at,
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
                  AND (i.issue_type='PULL_REQUEST' OR NOT
                """ + PULL_REQUEST_SIBLING + ")";
    String REVIEW_ROWS = """
                SELECT i.id parent_id, r.id target_id, r.author_id actor_id, r.submitted_at occurred_at,
                       CASE WHEN r.state='DISMISSED' THEN 'REVIEW_COMMENTED' ELSE 'REVIEW_' || r.state END event_type,
                       CASE WHEN r.state='DISMISSED' THEN 'review.commented' ELSE 'review.' || lower(r.state) END key_type,
                       'review' target_type
                FROM pull_request_review r JOIN issue i ON i.id=r.pull_request_id
                WHERE i.repository_id=:repositoryId AND i.deleted_at IS NULL
                  AND r.state IN ('APPROVED','CHANGES_REQUESTED','COMMENTED','DISMISSED','UNKNOWN')
                  AND NOT (
                """ + ActivityEventRepository.REPLY_ONLY_REVIEW + ")";
    String REPLY_REVIEW_ROWS = """
                SELECT i.id parent_id, r.id target_id, r.author_id actor_id, r.submitted_at occurred_at,
                       'REVIEW_COMMENTED' event_type, 'review.commented' key_type, 'review' target_type
                FROM pull_request_review r JOIN issue i ON i.id=r.pull_request_id
                WHERE i.repository_id=:repositoryId AND (
                """ + ActivityEventRepository.REPLY_ONLY_REVIEW + ")";
    String COMMENT_ROWS = """
                SELECT i.id parent_id, c.id target_id, c.author_id actor_id, c.created_at occurred_at,
                       'COMMENT_CREATED' event_type, 'comment.created' key_type, 'issue_comment' target_type
                FROM issue_comment c JOIN issue i ON i.id=c.issue_id
                WHERE i.repository_id=:repositoryId AND i.deleted_at IS NULL
                """;
    String REVIEW_COMMENT_ROWS = """
                SELECT i.id parent_id, c.id target_id, c.author_id actor_id, c.created_at occurred_at,
                       'REVIEW_COMMENT_CREATED' event_type, 'review_comment.created' key_type,
                       'review_comment' target_type
                FROM pull_request_review_comment c JOIN issue i ON i.id=c.pull_request_id
                WHERE i.repository_id=:repositoryId AND i.deleted_at IS NULL
                """;

    String SOURCES = "SELECT 'INVALID_ISSUE' source_type, s.* FROM (" + INVALID_ISSUE_ROWS + ") s" + " UNION ALL "
            + "SELECT 'ISSUE' source_type, s.* FROM (" + ISSUE_ROWS + ") s" + " UNION ALL "
            + "SELECT 'REVIEW' source_type, s.* FROM ("
            + REVIEW_ROWS + ") s" + " UNION ALL " + "SELECT 'REPLY_REVIEW' source_type, s.* FROM ("
            + REPLY_REVIEW_ROWS + ") s" + " UNION ALL " + "SELECT 'COMMENT' source_type, s.* FROM ("
            + COMMENT_ROWS + ") s" + " UNION ALL " + "SELECT 'REVIEW_COMMENT' source_type, s.* FROM ("
            + REVIEW_COMMENT_ROWS + ") s";
    String CANDIDATES = "WITH candidate AS (SELECT * FROM (" + SOURCES + """
            ) source WHERE source_type=:source AND parent_id > :after AND parent_id <= :last)
            """;

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM repository r JOIN repository_to_monitor m
              ON m.native_id=r.native_id
            WHERE m.workspace_id=:workspaceId AND r.id=:repositoryId AND r.provider_id=:providerId)
            """, nativeQuery = true)
    boolean isMonitored(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryId") long repositoryId,
            @Param("providerId") long providerId);

    @WorkspaceAgnostic("Provider source rows are shared; repository identity bounds this read")
    @Query(value = "SELECT coalesce(max(id),0) FROM issue WHERE repository_id=:repositoryId", nativeQuery = true)
    long findCeiling(@Param("repositoryId") long repositoryId);

    /**
     * The repository's next issues and pull requests by id. Every source row hangs off one of them, so a
     * chunk reads only its own rows: a chunk by source row id reads the whole id range of every repository.
     */
    @WorkspaceAgnostic("Provider source rows are shared; repository identity bounds this read")
    @Query(value = """
            SELECT id FROM issue WHERE repository_id=:repositoryId AND id > :after AND id <= :ceiling
            ORDER BY id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findChunkParents(
            @Param("repositoryId") long repositoryId,
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

    /**
     * Removes this workspace's issue lifecycle facts for invalid targets; their comments, reviews and pull
     * request facts stay. The candidate rows bound the targets to the repository.
     */
    @Modifying
    @Query(value = CANDIDATES + """
            DELETE FROM activity_event e USING candidate c
            WHERE e.workspace_id=:workspaceId AND e.repository_id=:repositoryId AND e.target_type='issue'
              AND e.event_type IN ('ISSUE_CREATED','ISSUE_CLOSED','ISSUE_REOPENED') AND e.target_id=c.target_id
            """, nativeQuery = true)
    int deleteInvalidIssueEvents(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryId") long repositoryId,
            @Param("source") String source,
            @Param("after") long after,
            @Param("last") long last);

    @WorkspaceAgnostic("Provider source rows are shared; reconcileRepository checks that the workspace monitors the"
            + " repository before any chunk is written")
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
              AND NOT EXISTS (SELECT 1 FROM identity_restriction s JOIN "user" u
                  ON u.provider_id=s.provider_id AND s.kind='ERASED' AND u.native_id::text=s.provider_user_key
                  WHERE u.id=c.actor_id AND s.team_key='')
              AND NOT EXISTS (SELECT 1 FROM activity_event e
                  WHERE e.workspace_id=:workspaceId AND e.target_type=c.target_type
                    AND e.target_id=c.target_id AND (c.target_type='review' OR e.event_type=c.event_type))
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
