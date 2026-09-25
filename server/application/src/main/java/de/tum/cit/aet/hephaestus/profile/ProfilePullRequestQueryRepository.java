package de.tum.cit.aet.hephaestus.profile;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Lives in the profile package (not integration.scm) because it joins integration.scm entities
 * with workspace entities (RepositoryToMonitor) — the integration.scm package must not
 * depend on workspace entities.
 */
@Repository
@WorkspaceAgnostic("Profile query helper joining with RepositoryToMonitor for workspace scoping")
public interface ProfilePullRequestQueryRepository extends JpaRepository<PullRequest, Long> {
    @Query("""
        SELECT p
        FROM PullRequest p
        LEFT JOIN FETCH p.labels
        JOIN FETCH p.author
        LEFT JOIN FETCH p.assignees
        LEFT JOIN FETCH p.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
        WHERE p.author.id = :authorId
            AND p.state IN :states
            AND (CAST(:since AS Instant) IS NULL OR p.createdAt >= :since)
            AND (CAST(:until AS Instant) IS NULL OR p.createdAt < :until)
            AND rtm.workspace.id = :workspaceId
        ORDER BY p.createdAt DESC
        """)
    List<PullRequest> findAuthoredByUserIdAndStates(
            @Param("authorId") Long authorId,
            @Param("states") Set<PullRequest.State> states,
            @Param("workspaceId") Long workspaceId,
            @Param("since") @Nullable Instant since,
            @Param("until") @Nullable Instant until);

    @Query("""
        SELECT p.author.id as authorId, COUNT(p) as count
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE p.author.id IN :authorIds
            AND p.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue$State.OPEN
            AND p.createdAt >= :since
            AND p.createdAt < :until
            AND rtm.workspace.id = :workspaceId
        GROUP BY p.author.id
        """)
    List<AuthorCountProjection> countOpenPullRequestsByAuthors(
            @Param("workspaceId") Long workspaceId,
            @Param("authorIds") Set<Long> authorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT p.author.id as authorId, COUNT(p) as count
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE p.author.id IN :authorIds
            AND EXISTS (
                SELECT 1 FROM TeamMembership tm
                WHERE tm.user = p.author
                AND tm.team.id IN :teamIds
            )
            AND p.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue$State.OPEN
            AND p.createdAt >= :since
            AND p.createdAt < :until
            AND rtm.workspace.id = :workspaceId
            AND EXISTS (
                SELECT 1 FROM TeamRepositoryPermission trp
                WHERE trp.repository = p.repository
                AND trp.team.id IN :teamIds
            )
            AND NOT EXISTS (
                SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
                WHERE wtrs.repository = p.repository
                AND wtrs.workspace.id = :workspaceId
                AND wtrs.team.id IN :teamIds
                AND wtrs.hiddenFromContributions = true
            )
        GROUP BY p.author.id
        """)
    List<AuthorCountProjection> countOpenPullRequestsByAuthorsAndTeams(
            @Param("workspaceId") Long workspaceId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("authorIds") Set<Long> authorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT p.author.id as authorId, COUNT(p) as count
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE p.author.id IN :authorIds
            AND p.mergedAt IS NOT NULL
            AND p.mergedAt >= :since
            AND p.mergedAt < :until
            AND rtm.workspace.id = :workspaceId
        GROUP BY p.author.id
        """)
    List<AuthorCountProjection> countMergedPullRequestsByAuthors(
            @Param("workspaceId") Long workspaceId,
            @Param("authorIds") Set<Long> authorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT p.author.id as authorId, COUNT(p) as count
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE p.author.id IN :authorIds
            AND EXISTS (
                SELECT 1 FROM TeamMembership tm
                WHERE tm.user = p.author
                AND tm.team.id IN :teamIds
            )
            AND p.mergedAt IS NOT NULL
            AND p.mergedAt >= :since
            AND p.mergedAt < :until
            AND rtm.workspace.id = :workspaceId
            AND EXISTS (
                SELECT 1 FROM TeamRepositoryPermission trp
                WHERE trp.repository = p.repository
                AND trp.team.id IN :teamIds
            )
            AND NOT EXISTS (
                SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
                WHERE wtrs.repository = p.repository
                AND wtrs.workspace.id = :workspaceId
                AND wtrs.team.id IN :teamIds
                AND wtrs.hiddenFromContributions = true
            )
        GROUP BY p.author.id
        """)
    List<AuthorCountProjection> countMergedPullRequestsByAuthorsAndTeams(
            @Param("workspaceId") Long workspaceId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("authorIds") Set<Long> authorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT p.author.id as authorId, COUNT(p) as count
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE p.author.id IN :authorIds
            AND p.closedAt IS NOT NULL
            AND p.closedAt >= :since
            AND p.closedAt < :until
            AND p.mergedAt IS NULL
            AND rtm.workspace.id = :workspaceId
        GROUP BY p.author.id
        """)
    List<AuthorCountProjection> countClosedPullRequestsByAuthors(
            @Param("workspaceId") Long workspaceId,
            @Param("authorIds") Set<Long> authorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
        SELECT p.author.id as authorId, COUNT(p) as count
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE p.author.id IN :authorIds
            AND EXISTS (
                SELECT 1 FROM TeamMembership tm
                WHERE tm.user = p.author
                AND tm.team.id IN :teamIds
            )
            AND p.closedAt IS NOT NULL
            AND p.closedAt >= :since
            AND p.closedAt < :until
            AND p.mergedAt IS NULL
            AND rtm.workspace.id = :workspaceId
            AND EXISTS (
                SELECT 1 FROM TeamRepositoryPermission trp
                WHERE trp.repository = p.repository
                AND trp.team.id IN :teamIds
            )
            AND NOT EXISTS (
                SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
                WHERE wtrs.repository = p.repository
                AND wtrs.workspace.id = :workspaceId
                AND wtrs.team.id IN :teamIds
                AND wtrs.hiddenFromContributions = true
            )
        GROUP BY p.author.id
        """)
    List<AuthorCountProjection> countClosedPullRequestsByAuthorsAndTeams(
            @Param("workspaceId") Long workspaceId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("authorIds") Set<Long> authorIds,
            @Param("since") Instant since,
            @Param("until") Instant until);

    interface AuthorCountProjection {
        Long getAuthorId();

        Long getCount();
    }
}
