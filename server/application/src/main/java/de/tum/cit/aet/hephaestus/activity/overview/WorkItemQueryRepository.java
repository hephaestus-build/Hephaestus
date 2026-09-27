package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Pull requests and issues for the Activity pages. Open work is scoped to the workspace's monitored
 * repositories; it lives here rather than in {@code integration.scm} because it joins workspace entities.
 */
@org.springframework.stereotype.Repository
public interface WorkItemQueryRepository extends Repository<Issue, Long> {

    String MONITORED = """
            EXISTS (
                SELECT 1 FROM RepositoryToMonitor rtm
                WHERE rtm.workspace.id = :workspaceId
                AND rtm.nameWithOwner = work.repository.nameWithOwner
            )
            """;

    @Query("""
            SELECT work FROM PullRequest work
            JOIN work.requestedReviewers reviewer
            LEFT JOIN FETCH work.repository
            LEFT JOIN FETCH work.author
            WHERE reviewer.id = :userId
            AND work.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue$State.OPEN
            AND work.isDraft = false
            AND work.deletedAt IS NULL
            AND (work.author IS NULL OR work.author.id <> :userId)
            AND
            """ + MONITORED + """
            ORDER BY work.updatedAt DESC NULLS LAST, work.id DESC
            """)
    Slice<PullRequest> findReviewRequests(
            @Param("workspaceId") long workspaceId, @Param("userId") long userId, Pageable pageable);

    @Query("""
            SELECT work FROM PullRequest work
            LEFT JOIN FETCH work.repository
            LEFT JOIN FETCH work.author
            WHERE work.author.id = :userId
            AND work.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue$State.OPEN
            AND work.deletedAt IS NULL
            AND
            """ + MONITORED + """
            ORDER BY work.updatedAt DESC NULLS LAST, work.id DESC
            """)
    Slice<PullRequest> findOpenPullRequests(
            @Param("workspaceId") long workspaceId, @Param("userId") long userId, Pageable pageable);

    @Query("""
            SELECT work FROM Issue work
            JOIN work.assignees assignee
            LEFT JOIN FETCH work.repository
            LEFT JOIN FETCH work.author
            WHERE TYPE(work) = Issue
            AND assignee.id = :userId
            AND work.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue$State.OPEN
            AND work.deletedAt IS NULL
            AND
            """ + MONITORED + """
            ORDER BY work.updatedAt DESC NULLS LAST, work.id DESC
            """)
    Slice<Issue> findAssignedIssues(
            @Param("workspaceId") long workspaceId, @Param("userId") long userId, Pageable pageable);

    @WorkspaceAgnostic("Hydrates targets of events already read from this workspace's activity ledger")
    @Query("""
            SELECT work FROM Issue work
            LEFT JOIN FETCH work.repository
            LEFT JOIN FETCH work.author
            WHERE work.id IN :ids
            """)
    List<Issue> findAllByIdIn(@Param("ids") Collection<Long> ids);
}
