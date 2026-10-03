package de.tum.cit.aet.hephaestus.integration.scm.context;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import jakarta.persistence.QueryHint;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The provider rows a job folder is rendered from, each as one JSON object of its stored columns. Every read is
 * limited to work in a repository the workspace monitors, and streams so a large repository is never held in
 * memory at once.
 */
interface ScmJobFolderRepository extends Repository<Issue, Long> {

    String MONITORED_BY_WORKSPACE = """
         AND EXISTS (SELECT 1 FROM repository_to_monitor m JOIN repository r ON r.name_with_owner = m.name_with_owner
                     WHERE m.workspace_id = :workspaceId AND r.id = i.repository_id)
        """;

    String COMMENT = "(to_jsonb(c) || jsonb_build_object('synced_at', NULL))::text";

    @Query(value = """
            SELECT (to_jsonb(i) || jsonb_build_object('synced_at', i.last_sync_at, 'labels', COALESCE(
              (SELECT jsonb_agg(l.name ORDER BY l.name COLLATE "C") FROM issue_label il JOIN label l ON l.id = il.label_id
               WHERE il.issue_id = i.id), '[]'::jsonb)))::text
            FROM issue i
            WHERE i.repository_id = :repositoryId AND i.deleted_at IS NULL
            """ + MONITORED_BY_WORKSPACE + " ORDER BY i.number", nativeQuery = true)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "256"))
    Stream<String> streamWork(@Param("workspaceId") long workspaceId, @Param("repositoryId") long repositoryId);

    @Query(
            value = "SELECT " + COMMENT
                    + " FROM issue_comment c JOIN issue i ON i.id = c.issue_id WHERE i.id = :issueId"
                    + MONITORED_BY_WORKSPACE + " ORDER BY c.id",
            nativeQuery = true)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "256"))
    Stream<String> streamIssueComments(@Param("workspaceId") long workspaceId, @Param("issueId") long issueId);

    @Query(
            value = "SELECT " + COMMENT + " FROM pull_request_review_comment c JOIN issue i ON i.id = c.pull_request_id"
                    + " WHERE i.id = :pullRequestId" + MONITORED_BY_WORKSPACE + " ORDER BY c.id",
            nativeQuery = true)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "256"))
    Stream<String> streamReviewComments(
            @Param("workspaceId") long workspaceId, @Param("pullRequestId") long pullRequestId);

    @Query(
            value = "SELECT " + COMMENT + " FROM pull_request_review c JOIN issue i ON i.id = c.pull_request_id"
                    + " WHERE i.id = :pullRequestId" + MONITORED_BY_WORKSPACE + " ORDER BY c.id",
            nativeQuery = true)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "256"))
    Stream<String> streamReviews(@Param("workspaceId") long workspaceId, @Param("pullRequestId") long pullRequestId);

    @Query(
            value = "SELECT " + COMMENT + " FROM pull_request_review_thread c JOIN issue i ON i.id = c.pull_request_id"
                    + " WHERE i.id = :pullRequestId" + MONITORED_BY_WORKSPACE + " ORDER BY c.id",
            nativeQuery = true)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "256"))
    Stream<String> streamReviewThreads(
            @Param("workspaceId") long workspaceId, @Param("pullRequestId") long pullRequestId);

    @Query(value = """
            SELECT jsonb_build_object('id', i.id, 'number', i.number, 'title', i.title, 'state', i.state,
              'author_id', i.author_id, 'url', i.html_url, 'synced_at', i.last_sync_at, 'repository', r.name_with_owner)::text
            FROM issue i JOIN repository r ON r.id = i.repository_id
            WHERE i.repository_id = :repositoryId AND i.issue_type = :issueType AND i.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM repository_to_monitor m
                          WHERE m.workspace_id = :workspaceId AND m.name_with_owner = r.name_with_owner)
            ORDER BY i.number
            """, nativeQuery = true)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "256"))
    Stream<String> streamInventory(
            @Param("workspaceId") long workspaceId,
            @Param("repositoryId") long repositoryId,
            @Param("issueType") String issueType);
}
