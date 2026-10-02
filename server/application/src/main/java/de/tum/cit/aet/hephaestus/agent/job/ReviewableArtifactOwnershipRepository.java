package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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
 * <p>{@code TYPE} discriminates in the ownership query: {@code Issue} and {@code PullRequest} share one table
 * under {@code SINGLE_TABLE} inheritance, so an id lookup without it would answer for the wrong kind.
 */
@org.springframework.stereotype.Repository
@WorkspaceAgnostic("Ownership is the question; the workspace id is the parameter it is asked about")
interface ReviewableArtifactOwnershipRepository extends JpaRepository<Issue, Long> {
    /** Which of these artifacts of exactly this type the workspace owns. */
    @Query("""
        SELECT i.id FROM Issue i
        WHERE i.id IN :ids AND TYPE(i) = :type
          AND EXISTS (
              SELECT 1 FROM RepositoryToMonitor rtm
              WHERE rtm.workspace.id = :workspaceId AND rtm.nameWithOwner = i.repository.nameWithOwner)
        """)
    Set<Long> findIdsInWorkspace(
            @Param("workspaceId") Long workspaceId,
            @Param("type") Class<? extends Issue> type,
            @Param("ids") Collection<Long> ids);

    default boolean belongsToWorkspace(long workspaceId, Class<? extends Issue> type, long id) {
        return !findIdsInWorkspace(workspaceId, type, List.of(id)).isEmpty();
    }

    /**
     * What each of these artifacts is called now, for the workspace's own artifacts only. No {@code TYPE}
     * discriminator: a pull request is an {@code Issue} row too, named off the same column. {@code EXISTS}
     * rather than a join, so two monitors of one repository do not repeat an id.
     */
    @Query("""
        SELECT i.id AS id, i.title AS title FROM Issue i
        WHERE i.id IN :ids
          AND EXISTS (
              SELECT 1 FROM RepositoryToMonitor rtm
              WHERE rtm.workspace.id = :workspaceId AND rtm.nameWithOwner = i.repository.nameWithOwner)
        """)
    List<ReviewedWorkTitle> findCurrentTitles(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<Long> ids);

    interface ReviewedWorkTitle {
        Long getId();

        String getTitle();
    }

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
