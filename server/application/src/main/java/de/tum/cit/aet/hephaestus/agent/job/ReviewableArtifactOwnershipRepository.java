package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Whether a workspace may act on a mirrored SCM artifact named by its surrogate id.
 *
 * <p>An artifact carries no workspace column, so ownership is a join through
 * {@code RepositoryToMonitor} rather than a column read off the row; that join lives here rather than on
 * the deliberately workspace-agnostic SCM repositories.
 *
 * <p>Kept separate from the gate loader's fetch queries rather than folded into them: those carry the
 * eager graph the review path needs, while ownership is one boolean not worth a second copy of a
 * five-way {@code JOIN FETCH} that could drift from the one under test.
 *
 * <p>{@code TYPE} discriminates in both artifact queries: {@code Issue} and {@code PullRequest} share one table
 * under {@code SINGLE_TABLE} inheritance, so an id lookup without it would answer for the wrong kind.
 */
@org.springframework.stereotype.Repository
@WorkspaceAgnostic("Ownership is the question; the workspace id is the parameter it is asked about")
interface ReviewableArtifactOwnershipRepository extends JpaRepository<Issue, Long> {
    @Query("""
        SELECT COUNT(p) > 0 FROM PullRequest p
        JOIN p.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
        WHERE rtm.workspace.id = :workspaceId AND p.id = :pullRequestId AND TYPE(p) = PullRequest
        """)
    boolean pullRequestBelongsToWorkspace(
            @Param("workspaceId") Long workspaceId, @Param("pullRequestId") Long pullRequestId);

    @Query("""
        SELECT COUNT(i) > 0 FROM Issue i
        JOIN i.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
        WHERE rtm.workspace.id = :workspaceId AND i.id = :issueId AND TYPE(i) = Issue
        """)
    boolean issueBelongsToWorkspace(@Param("workspaceId") Long workspaceId, @Param("issueId") Long issueId);

    /**
     * The repositories this workspace monitors under {@code nameWithOwner} on the provider server at
     * {@code serverUrl}, with that provider row fetched so the caller can check its kind.
     *
     * <p>Unlike the two checks above, the provider server is part of the join: a name is only unique per
     * provider, and the same namespace and project can exist on two GitLab servers, of which the
     * workspace is connected to one. Case-insensitive because providers route paths that way, so a
     * caller must refuse anything but exactly one row rather than pick one. A monitor that already
     * recorded the provider's native id must agree with it; older rows without one still match by name.
     */
    @Query("""
        SELECT DISTINCT r FROM Repository r
        JOIN FETCH r.provider p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
        WHERE rtm.workspace.id = :workspaceId
          AND p.serverUrl = :serverUrl
          AND LOWER(r.nameWithOwner) = LOWER(:nameWithOwner)
          AND (rtm.nativeId IS NULL OR rtm.nativeId = r.nativeId)
        """)
    List<Repository> findMonitoredRepositories(
            @Param("workspaceId") long workspaceId,
            @Param("serverUrl") String serverUrl,
            @Param("nameWithOwner") String nameWithOwner);
}
