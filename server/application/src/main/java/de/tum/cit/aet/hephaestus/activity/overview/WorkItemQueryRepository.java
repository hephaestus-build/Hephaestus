package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
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
            JOIN work.requestedReviewers request
            LEFT JOIN FETCH work.repository
            LEFT JOIN FETCH work.author
            JOIN FETCH work.provider
            WHERE request.user.id = :userId
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

    /**
     * Pull requests that ask one of {@code teamIds} for a review, and not the member: not theirs, not asking them
     * directly, and not yet approved or sent back by them.
     */
    @Query("""
            SELECT work FROM PullRequest work
            LEFT JOIN FETCH work.repository
            LEFT JOIN FETCH work.author
            JOIN FETCH work.provider
            WHERE EXISTS (
                SELECT 1 FROM RequestedTeam teamRequest
                WHERE teamRequest.pullRequest = work
                AND teamRequest.team.id IN :teamIds
            )
            AND work.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue$State.OPEN
            AND work.isDraft = false
            AND work.deletedAt IS NULL
            AND (work.author IS NULL OR work.author.id <> :userId)
            AND NOT EXISTS (
                SELECT 1 FROM RequestedReviewer request
                WHERE request.pullRequest = work
                AND request.user.id = :userId
            )
            AND NOT EXISTS (
                SELECT 1 FROM PullRequestReview review
                WHERE review.pullRequest = work
                AND review.author.id = :userId
                AND
            """ + PullRequestReview.VERDICT + """
            )
            AND
            """ + MONITORED + """
            ORDER BY work.updatedAt DESC NULLS LAST, work.id DESC
            """)
    Slice<PullRequest> findTeamReviewRequests(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId,
            @Param("teamIds") Collection<Long> teamIds,
            Pageable pageable);

    @Query("""
            SELECT work FROM PullRequest work
            LEFT JOIN FETCH work.repository
            LEFT JOIN FETCH work.author
            JOIN FETCH work.provider
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

    @WorkspaceAgnostic("Hydrates pull requests already read from this workspace's open work")
    @Query("""
            SELECT request.pullRequest.id AS pullRequestId, reviewer AS reviewer, request.reviewState AS reviewState
            FROM RequestedReviewer request
            JOIN request.user reviewer
            WHERE request.pullRequest.id IN :ids
            AND reviewer.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
            """)
    List<ReviewRequest> findRequestedReviewers(@Param("ids") Collection<Long> ids);

    @WorkspaceAgnostic("Hydrates pull requests already read from this workspace's open work")
    @Query("""
            SELECT work.id AS pullRequestId, team.id AS teamId, team.name AS teamName
            FROM PullRequest work
            JOIN work.requestedTeams teamRequest
            JOIN teamRequest.team team
            WHERE work.id IN :ids
            AND team.id IN :teamIds
            """)
    List<TeamReviewRequest> findRequestedTeams(
            @Param("ids") Collection<Long> ids, @Param("teamIds") Collection<Long> teamIds);

    @WorkspaceAgnostic("Hydrates pull requests already read from this workspace's open work")
    @Query("""
            SELECT review.pullRequest.id AS pullRequestId, author AS reviewer, review.state AS state,
                review.submittedAt AS submittedAt, review.id AS id
            FROM PullRequestReview review
            JOIN review.author author
            WHERE review.pullRequest.id IN :ids
            AND
            """ + PullRequestReview.STANDING + """
            AND author.type = de.tum.cit.aet.hephaestus.integration.scm.domain.user.User$Type.USER
            """)
    List<StandingReview> findStandingReviews(@Param("ids") Collection<Long> ids);

    interface ReviewRequest {
        Long getPullRequestId();

        User getReviewer();

        RequestedReviewer.@Nullable ReviewState getReviewState();
    }

    interface TeamReviewRequest {
        Long getPullRequestId();

        Long getTeamId();

        String getTeamName();
    }

    interface StandingReview {
        Long getPullRequestId();

        User getReviewer();

        PullRequestReview.State getState();

        Instant getSubmittedAt();

        Long getId();
    }
}
