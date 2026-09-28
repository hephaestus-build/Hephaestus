package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
 * <p>{@code TYPE} discriminates in both queries: {@code Issue} and {@code PullRequest} share one table
 * under {@code SINGLE_TABLE} inheritance, so an id lookup without it would answer for the wrong kind.
 */
@Repository
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
     * What each of these artifacts is called now, for the workspace's own artifacts only. Deliberately
     * without a {@code TYPE} discriminator, unlike the two ownership queries: a pull request is an
     * {@code Issue} row too, and both kinds are named off the same column.
     */
    @Query("""
        SELECT i.id AS id, i.title AS title FROM Issue i
        JOIN i.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
        WHERE rtm.workspace.id = :workspaceId AND i.id IN :ids
        """)
    List<ReviewedWorkTitle> findCurrentTitles(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<Long> ids);

    interface ReviewedWorkTitle {
        Long getId();

        String getTitle();
    }
}
