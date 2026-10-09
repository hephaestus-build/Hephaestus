package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Read-side queries for the mentor content sources. {@code User} is the bound root only because
 * Spring Data requires one; the queries themselves cross several entities, each
 * workspace-scoped through {@code RepositoryToMonitor} or {@code Practice.workspace}.
 */
@Repository
@WorkspaceAgnostic("Mentor context queries take workspace ID parameters")
public interface MentorContextQueryRepository extends JpaRepository<User, Long> {

    /**
     * HQL: the repository bound as {@code hiddenRepository} is not one a team of this workspace hides from
     * contributions — the rule Activity and the review history apply, so no list here names work whose reviews Heph
     * may not show. Needs {@code :workspaceId}; a query binds the alias with {@code hiddenRepository = ...}.
     */
    String NOT_HIDDEN = """
                  AND NOT EXISTS (
                      SELECT 1 FROM WorkspaceTeamRepositorySettings wtrs
                      WHERE wtrs.workspace.id = :workspaceId
                        AND wtrs.hiddenFromContributions = true
                        AND wtrs.repository =\s""";

    /**
     * Single-round-trip snapshot of every counter {@code UserContentSource} needs. The JPQL
     * anchors on {@code User} so the constructor expression evaluates exactly once (one row
     * by PK); each bucket is a {@code COALESCE((SELECT …), 0L)} so the projected longs are
     * never null. The nine metric counts ride in one statement rather than one round trip each.
     */
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MentorUserCounts(
            COALESCE((SELECT COUNT(p1)
                FROM PullRequest p1
                JOIN RepositoryToMonitor rtm1 ON rtm1.nameWithOwner = p1.repository.nameWithOwner
                    AND monitor_covers_repository(rtm1.workspace.id, rtm1.nativeId, p1.repository.nativeId, p1.repository.provider.id) = true
                WHERE p1.author.id = :userId
                  AND p1.deletedAt IS NULL
                  AND rtm1.workspace.id = :workspaceId
                  AND p1.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "p1.repository)" + """
                  AND p1.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN), 0L),
            COALESCE((SELECT COUNT(p2)
                FROM PullRequest p2
                JOIN RepositoryToMonitor rtm2 ON rtm2.nameWithOwner = p2.repository.nameWithOwner
                    AND monitor_covers_repository(rtm2.workspace.id, rtm2.nativeId, p2.repository.nativeId, p2.repository.provider.id) = true
                WHERE p2.author.id = :userId
                  AND p2.deletedAt IS NULL
                  AND rtm2.workspace.id = :workspaceId
                  AND p2.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "p2.repository)" + """
                  AND p2.isMerged = true
                  AND p2.mergedAt > :weekAgo
                  AND p2.mergedAt <= :now), 0L),
            COALESCE((SELECT COUNT(p3)
                FROM PullRequest p3
                JOIN RepositoryToMonitor rtm3 ON rtm3.nameWithOwner = p3.repository.nameWithOwner
                    AND monitor_covers_repository(rtm3.workspace.id, rtm3.nativeId, p3.repository.nativeId, p3.repository.provider.id) = true
                WHERE p3.author.id = :userId
                  AND p3.deletedAt IS NULL
                  AND rtm3.workspace.id = :workspaceId
                  AND p3.repository.provider.id = :providerId
        """ + NOT_HIDDEN
            + "p3.repository)" + """
                  AND p3.isMerged = true
                  AND p3.mergedAt > :twoWeeksAgo
                  AND p3.mergedAt <= :weekAgo), 0L),
            COALESCE((SELECT COUNT(i)
                FROM Issue i
                JOIN RepositoryToMonitor rtmi ON rtmi.nameWithOwner = i.repository.nameWithOwner
                    AND monitor_covers_repository(rtmi.workspace.id, rtmi.nativeId, i.repository.nativeId, i.repository.provider.id) = true
                WHERE TYPE(i) = Issue
                  AND i.author.id = :userId
                  AND i.deletedAt IS NULL
                  AND rtmi.workspace.id = :workspaceId
                  AND i.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "i.repository)" + """
                  AND i.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN), 0L),
            COALESCE((SELECT COUNT(r1)
                FROM PullRequestReview r1
                JOIN RepositoryToMonitor rtmr1 ON rtmr1.nameWithOwner = r1.pullRequest.repository.nameWithOwner
                    AND monitor_covers_repository(rtmr1.workspace.id, rtmr1.nativeId, r1.pullRequest.repository.nativeId, r1.pullRequest.repository.provider.id) = true
                WHERE r1.author.id = :userId
                  AND r1.pullRequest.deletedAt IS NULL
                  AND rtmr1.workspace.id = :workspaceId
                  AND r1.pullRequest.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "r1.pullRequest.repository)"
            + """
                  AND r1.submittedAt > :weekAgo
                  AND r1.submittedAt <= :now), 0L),
            COALESCE((SELECT COUNT(r2)
                FROM PullRequestReview r2
                JOIN RepositoryToMonitor rtmr2 ON rtmr2.nameWithOwner = r2.pullRequest.repository.nameWithOwner
                    AND monitor_covers_repository(rtmr2.workspace.id, rtmr2.nativeId, r2.pullRequest.repository.nativeId, r2.pullRequest.repository.provider.id) = true
                WHERE r2.author.id = :userId
                  AND r2.pullRequest.deletedAt IS NULL
                  AND rtmr2.workspace.id = :workspaceId
                  AND r2.pullRequest.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "r2.pullRequest.repository)" + """
                  AND r2.submittedAt > :twoWeeksAgo
                  AND r2.submittedAt <= :weekAgo), 0L),
            COALESCE((SELECT COUNT(r3)
                FROM PullRequestReview r3
                JOIN RepositoryToMonitor rtmr3 ON rtmr3.nameWithOwner = r3.pullRequest.repository.nameWithOwner
                    AND monitor_covers_repository(rtmr3.workspace.id, rtmr3.nativeId, r3.pullRequest.repository.nativeId, r3.pullRequest.repository.provider.id) = true
                WHERE r3.pullRequest.author.id = :userId
                  AND r3.pullRequest.deletedAt IS NULL
                  AND rtmr3.workspace.id = :workspaceId
                  AND r3.pullRequest.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "r3.pullRequest.repository)" + """
                  AND r3.submittedAt > :weekAgo
                  AND r3.submittedAt <= :now), 0L),
            COALESCE((SELECT COUNT(work)
                FROM PullRequest work
                JOIN RepositoryToMonitor rtmpr ON rtmpr.nameWithOwner = work.repository.nameWithOwner
                    AND monitor_covers_repository(rtmpr.workspace.id, rtmpr.nativeId, work.repository.nativeId, work.repository.provider.id) = true
                JOIN work.requestedReviewers request
                WHERE request.user.id = :userId
                  AND work.deletedAt IS NULL
                  AND work.isDraft = false
                  AND rtmpr.workspace.id = :workspaceId
                  AND work.repository.provider.id = :providerId
        """
            + NOT_HIDDEN + "work.repository)" + """
                  AND work.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
                  AND
        """ + RequestedReviewer.AWAITING_REVIEW + """
                ), 0L),
            COALESCE((SELECT COUNT(t)
                FROM PullRequestReviewThread t
                JOIN RepositoryToMonitor rtmt ON rtmt.nameWithOwner = t.pullRequest.repository.nameWithOwner
                    AND monitor_covers_repository(rtmt.workspace.id, rtmt.nativeId, t.pullRequest.repository.nativeId, t.pullRequest.repository.provider.id) = true
                WHERE t.pullRequest.author.id = :userId
                  AND t.pullRequest.deletedAt IS NULL
                  AND rtmt.workspace.id = :workspaceId
                  AND t.pullRequest.repository.provider.id = :providerId
        """ + NOT_HIDDEN
            + "t.pullRequest.repository)" + """
                  AND t.pullRequest.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
                  AND t.state = de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread.State.UNRESOLVED), 0L)
        )
        FROM User u
        WHERE u.id = :userId
        """)
    MentorUserCounts fetchUserCounts(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            @Param("twoWeeksAgo") Instant twoWeeksAgo,
            @Param("weekAgo") Instant weekAgo,
            @Param("now") Instant now);

    /**
     * Open issues assigned to user, ordered most recent first, scoped to workspace.
     *
     * <p>{@code TYPE(i) = Issue} excludes the {@code PullRequest} subclass rows that share this table
     * under single-table inheritance — without it a GitLab workspace lists merge requests as "assigned
     * open issues", and this list disagrees with the typed open-issue count in
     * {@link #fetchUserCounts}, which already carries the discriminator.
     */
    @Query("""
        SELECT i
        FROM Issue i
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = i.repository.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, i.repository.nativeId, i.repository.provider.id) = true
        JOIN i.assignees a
        LEFT JOIN FETCH i.repository
        LEFT JOIN FETCH i.milestone
        WHERE TYPE(i) = Issue
          AND a.id = :userId
          AND i.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND i.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "i.repository)" + """
          AND i.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
        ORDER BY i.createdAt DESC
        """)
    List<Issue> findAssignedOpenIssues(
            @Param("workspaceId") Long workspaceId, @Param("providerId") Long providerId, @Param("userId") Long userId);

    /**
     * Open, ready PRs that ask the user directly for a review they still owe ({@link RequestedReviewer#AWAITING_REVIEW}),
     * including those Activity shows as covered by other reviewers; a request to one of the user's teams is not one.
     * Author and repository fetched.
     */
    @Query("""
        SELECT work
        FROM PullRequest work
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = work.repository.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, work.repository.nativeId, work.repository.provider.id) = true
        JOIN work.requestedReviewers request
        LEFT JOIN FETCH work.author
        LEFT JOIN FETCH work.repository
        WHERE request.user.id = :userId
          AND work.deletedAt IS NULL
          AND work.isDraft = false
          AND rtm.workspace.id = :workspaceId
          AND work.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "work.repository)" + """
          AND work.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
          AND
        """ + RequestedReviewer.AWAITING_REVIEW + """
        ORDER BY work.createdAt DESC
        """)
    List<PullRequest> findPendingReviewRequestPrs(
            @Param("workspaceId") Long workspaceId, @Param("providerId") Long providerId, @Param("userId") Long userId);

    // Observations context — reviews received in window

    /**
     * Reviews received on user's authored PRs within {@code since}..now, scoped to workspace.
     * Page the cap into the DB query — a heavy reviewer's 90-day window can return hundreds of
     * rows; surfacing more than {@code MAX_RECENT_REVIEWS} is wasted work.
     */
    @Query("""
        SELECT prr
        FROM PullRequestReview prr
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = prr.pullRequest.repository.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, prr.pullRequest.repository.nativeId, prr.pullRequest.repository.provider.id) = true
        LEFT JOIN FETCH prr.author
        LEFT JOIN FETCH prr.pullRequest
        WHERE prr.pullRequest.author.id = :userId
          AND prr.pullRequest.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND prr.pullRequest.repository.provider.id = :providerId
          AND prr.submittedAt > :since
        """ + NOT_HIDDEN + "prr.pullRequest.repository)" + """
        ORDER BY prr.submittedAt DESC
        """)
    List<PullRequestReview> findReviewsReceivedSince(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            @Param("since") Instant since,
            Pageable page);

    /**
     * The developer's own authored PULL REQUESTS in the workspace, most recently active first — the work itself (not
     * observations about it), so the mentor has a concrete, linkable inventory of what they shipped. Same
     * author + RepositoryToMonitor scoping as {@link #findReviewsReceivedSince}. {@code FROM PullRequest}
     * narrows to the PR discriminator (single-table inheritance), so issues never leak in.
     */
    @Query("""
        SELECT p
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, p.repository.nativeId, p.repository.provider.id) = true
        WHERE p.author.id = :userId
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND p.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "p.repository)" + """
        ORDER BY COALESCE(p.updatedAt, p.createdAt) DESC NULLS LAST, p.id DESC
        """)
    List<PullRequest> findRecentAuthoredPullRequests(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            Pageable page);

    /** The stored copies of {@code ids} on the workspace's connected instance, for comparing with what was reviewed. */
    @Query("""
        SELECT p.id AS id, p.title AS title, p.body AS body, p.headRefOid AS head
        FROM PullRequest p
        JOIN p.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, r.nativeId, r.provider.id) = true
        WHERE p.id IN :ids
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND r.provider.id = :providerId
        """)
    List<StoredWork> findStoredPullRequests(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("ids") Collection<Long> ids);

    /** {@link #findStoredPullRequests} for issues; an issue has no head. */
    @Query("""
        SELECT i.id AS id, i.title AS title, i.body AS body, CAST(NULL AS String) AS head
        FROM Issue i
        JOIN i.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, r.nativeId, r.provider.id) = true
        WHERE TYPE(i) = Issue
          AND i.id IN :ids
          AND i.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND r.provider.id = :providerId
        """)
    List<StoredWork> findStoredIssues(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("ids") Collection<Long> ids);

    interface StoredWork {
        Long getId();

        @Nullable
        String getTitle();

        @Nullable
        String getBody();

        @Nullable
        String getHead();
    }

    /**
     * The developer's open authored pull requests on the workspace's connected instance, most recently
     * updated first. The provider predicate keeps a same-path project on another instance out: the
     * monitor matches by path alone.
     */
    @Query("""
        SELECT p
        FROM PullRequest p
        JOIN p.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, r.nativeId, r.provider.id) = true
        WHERE p.author.id = :userId
          AND p.deletedAt IS NULL
          AND p.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
          AND rtm.workspace.id = :workspaceId
          AND r.provider.id = :providerId
        """ + NOT_HIDDEN + "r)" + """
        ORDER BY p.updatedAt DESC NULLS LAST, p.id DESC
        """)
    List<PullRequest> findOpenAuthoredPullRequestsOnInstance(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("providerId") Long providerId,
            Pageable page);

    /**
     * One pull request the developer authored, in whatever state is stored — open, closed or merged — under the
     * scope of {@link #findOpenAuthoredPullRequestsOnInstance} otherwise: monitored by the workspace, on its connected
     * instance, not tombstoned.
     */
    @Query("""
        SELECT p
        FROM PullRequest p
        JOIN p.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, r.nativeId, r.provider.id) = true
        WHERE p.id = :pullRequestId
          AND p.author.id = :userId
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND r.provider.id = :providerId
        """ + NOT_HIDDEN + "r)" + """
        """)
    List<PullRequest> findAuthoredPullRequestOnInstance(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("providerId") Long providerId,
            @Param("pullRequestId") Long pullRequestId);

    /**
     * The issues the provider records {@code pullRequestId} as closing — GitHub's closing references, GitLab's
     * closes-issues — under the same scope as {@link #findAuthoredPullRequestOnInstance}: a pull request the
     * developer authored, in any stored state, monitored by the workspace, on its connected instance. Only issues of
     * the pull request's own repository that are not tombstoned, lowest number first; a caller passes its cap plus
     * one to learn whether more exist.
     */
    @Query("""
        SELECT DISTINCT i
        FROM PullRequest p
        JOIN p.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, r.nativeId, r.provider.id) = true
        JOIN p.closingIssues i
        WHERE p.id = :pullRequestId
          AND p.author.id = :userId
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND r.provider.id = :providerId
          AND TYPE(i) = Issue
          AND i.repository = p.repository
          AND i.deletedAt IS NULL
        ORDER BY i.number ASC, i.id ASC
        """)
    List<Issue> findClosingIssuesOfAuthoredPullRequest(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("providerId") Long providerId,
            @Param("pullRequestId") Long pullRequestId,
            Pageable page);

    /** Which of {@code refs} the delivery ledger records Hephaestus posting as feedback on this pull request. */
    @Query("""
        SELECT fp.postedCommentRef
        FROM FeedbackPlacement fp
        WHERE fp.feedback.workspaceId = :workspaceId
          AND fp.feedback.artifactKind = :artifactKind
          AND fp.feedback.artifactId = :pullRequestId
          AND fp.postedCommentRef IN :refs
        """)
    Set<String> findPostedCommentRefs(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("pullRequestId") Long pullRequestId,
            @Param("refs") Collection<String> refs);

    /** A pull request's inline comments, a thread at a time: unresolved threads first, each thread oldest first. */
    @Query("""
        SELECT c
        FROM PullRequestReviewComment c
        JOIN FETCH c.thread t
        LEFT JOIN FETCH t.resolvedBy
        LEFT JOIN FETCH c.author
        WHERE c.pullRequest.id = :pullRequestId
          AND c.pullRequest.deletedAt IS NULL
        ORDER BY CASE WHEN t.state = de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread.State.UNRESOLVED THEN 0 ELSE 1 END,
                 t.id DESC, c.createdAt ASC, c.id ASC
        """)
    List<PullRequestReviewComment> findThreadCommentsUnresolvedFirst(
            @Param("pullRequestId") Long pullRequestId, Pageable page);

    /**
     * The developer's own authored ISSUES (excluding PRs via {@code TYPE(i) = Issue}) in the workspace,
     * most recently active first. Companion to {@link #findRecentAuthoredPullRequests}.
     */
    @Query("""
        SELECT i
        FROM Issue i
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = i.repository.nameWithOwner
            AND monitor_covers_repository(rtm.workspace.id, rtm.nativeId, i.repository.nativeId, i.repository.provider.id) = true
        WHERE i.author.id = :userId
          AND i.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND i.repository.provider.id = :providerId
          AND TYPE(i) = Issue
        """ + NOT_HIDDEN + "i.repository)" + """
        ORDER BY COALESCE(i.updatedAt, i.createdAt) DESC NULLS LAST, i.id DESC
        """)
    List<Issue> findRecentAuthoredIssues(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            Pageable page);

    /** Number, title and link of pull requests by id, for naming the work a review-history row is about. */
    @Query("""
        SELECT p.id AS id, p.number AS number, p.title AS title, p.htmlUrl AS url
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE p.id IN :ids
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND p.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "p.repository)")
    List<WorkRef> findPullRequestRefs(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("ids") Collection<Long> ids);

    /** {@link #findPullRequestRefs} for issues. */
    @Query("""
        SELECT i.id AS id, i.number AS number, i.title AS title, i.htmlUrl AS url
        FROM Issue i
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = i.repository.nameWithOwner
        WHERE TYPE(i) = Issue
          AND i.id IN :ids
          AND i.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND i.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "i.repository)")
    List<WorkRef> findIssueRefs(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("ids") Collection<Long> ids);

    interface WorkRef {
        Long getId();

        Integer getNumber();

        @Nullable
        String getTitle();

        @Nullable
        String getUrl();
    }

    /** Which requested reviewers of a pull request still owe it a verdict ({@link RequestedReviewer#AWAITING_REVIEW}). */
    @Query("""
        SELECT request.user.id
        FROM PullRequest work
        JOIN work.requestedReviewers request
        WHERE work.id = :pullRequestId
          AND work.deletedAt IS NULL
          AND
        """ + RequestedReviewer.AWAITING_REVIEW)
    Set<Long> findAwaitingReviewerIds(@Param("pullRequestId") Long pullRequestId);

    /** The commits the provider associates with a pull request, oldest first. */
    @Query("""
        SELECT c
        FROM Commit c
        JOIN c.associatedPullRequests p
        WHERE p.id = :pullRequestId
          AND p.deletedAt IS NULL
        ORDER BY c.authoredAt ASC, c.id ASC
        """)
    List<Commit> findCommitsOfPullRequest(@Param("pullRequestId") Long pullRequestId, Pageable page);

    /** How many of a pull request's stored commits there are, and how many have their file changes captured. */
    @Query("""
        SELECT COUNT(c) AS stored, COUNT(c.gitDetailsCapturedAt) AS captured
        FROM Commit c
        JOIN c.associatedPullRequests p
        WHERE p.id = :pullRequestId
          AND p.deletedAt IS NULL
        """)
    CommitCoverage countCommitsOfPullRequest(@Param("pullRequestId") Long pullRequestId);

    interface CommitCoverage {
        long getStored();

        long getCaptured();
    }

    /** The file changes of a pull request's captured commits, a commit at a time. */
    @Query("""
        SELECT f
        FROM CommitFileChange f
        JOIN f.commit c
        JOIN c.associatedPullRequests p
        WHERE p.id = :pullRequestId
          AND p.deletedAt IS NULL
        ORDER BY c.authoredAt ASC, c.id ASC, f.id ASC
        """)
    List<CommitFileChange> findFileChangesOfPullRequest(@Param("pullRequestId") Long pullRequestId, Pageable page);

    /**
     * The pull requests the provider records as closing {@code issueId}, newest first, each under the scope its issue
     * was read with: monitored by the workspace, on its connected instance, not tombstoned and not hidden.
     */
    @Query("""
        SELECT p
        FROM PullRequest p
        JOIN p.closingIssues i
        LEFT JOIN FETCH p.author
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE i.id = :issueId
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND p.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "p.repository)" + """
        ORDER BY p.createdAt DESC, p.id DESC
        """)
    List<PullRequest> findPullRequestsClosingIssue(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("issueId") Long issueId,
            Pageable page);

    /**
     * The related issues an issue names, each under the scope the issue was read with. Related work can sit in another
     * repository, so each relation is read with the predicates of its own, never loaded and then filtered.
     */
    String RELATED_IN_SCOPE = """
          AND r.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND repo.provider.id = :providerId
        """ + NOT_HIDDEN + "repo)" + """
        ORDER BY r.number ASC, r.id ASC
        """;

    /** The issue's parent, when it is in scope. */
    @Query("""
        SELECT r
        FROM Issue i
        JOIN i.parentIssue r
        JOIN r.repository repo
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = repo.nameWithOwner
        WHERE i.id = :issueId
          AND TYPE(i) = Issue
          AND i.deletedAt IS NULL
          AND TYPE(r) = Issue
        """ + RELATED_IN_SCOPE)
    List<Issue> findParentInScope(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("issueId") Long issueId,
            Pageable page);

    /** The issue's sub-issues in scope. */
    @Query("""
        SELECT r
        FROM Issue r
        JOIN r.repository repo
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = repo.nameWithOwner
        WHERE r.parentIssue.id = :issueId
          AND TYPE(r) = Issue
        """ + RELATED_IN_SCOPE)
    List<Issue> findSubIssuesInScope(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("issueId") Long issueId,
            Pageable page);

    /** The issues in scope that block the issue. */
    @Query("""
        SELECT r
        FROM Issue i
        JOIN i.blockedBy r
        JOIN r.repository repo
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = repo.nameWithOwner
        WHERE i.id = :issueId
          AND TYPE(i) = Issue
          AND i.deletedAt IS NULL
          AND TYPE(r) = Issue
        """ + RELATED_IN_SCOPE)
    List<Issue> findBlockersInScope(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("issueId") Long issueId,
            Pageable page);

    /** The issues in scope that the issue blocks. */
    @Query("""
        SELECT r
        FROM Issue i
        JOIN i.blocking r
        JOIN r.repository repo
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = repo.nameWithOwner
        WHERE i.id = :issueId
          AND TYPE(i) = Issue
          AND i.deletedAt IS NULL
          AND TYPE(r) = Issue
        """ + RELATED_IN_SCOPE)
    List<Issue> findBlockedInScope(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("issueId") Long issueId,
            Pageable page);

    /**
     * One issue the developer authored or is assigned to, in any stored state, monitored by the workspace, on its
     * connected instance, not tombstoned and not hidden — the scope of {@link #findAuthoredPullRequestOnInstance}.
     */
    @Query("""
        SELECT DISTINCT i
        FROM Issue i
        JOIN i.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
        LEFT JOIN i.assignees assignee
        WHERE TYPE(i) = Issue
          AND i.id = :issueId
          AND i.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND r.provider.id = :providerId
          AND (i.author.id = :userId OR assignee.id = :userId)
        """ + NOT_HIDDEN + "r)")
    List<Issue> findOwnIssueOnInstance(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("providerId") Long providerId,
            @Param("issueId") Long issueId);

    // Activity: the developer's own dated actions, in monitored repositories no team hides

    /** Pull requests the developer authored that opened, merged or closed since {@code since}, latest first. */
    @Query("""
        SELECT p
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        LEFT JOIN FETCH p.repository
        WHERE p.author.id = :userId
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND p.repository.provider.id = :providerId
          AND (p.createdAt >= :since OR p.mergedAt >= :since OR p.closedAt >= :since)
        """ + NOT_HIDDEN + "p.repository)" + """
        ORDER BY COALESCE(p.mergedAt, p.closedAt, p.createdAt) DESC, p.id DESC
        """)
    List<PullRequest> findAuthoredPullRequestsActiveSince(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            @Param("since") Instant since,
            Pageable page);

    /** Issues the developer authored that opened or closed since {@code since}, latest first. */
    @Query("""
        SELECT i
        FROM Issue i
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = i.repository.nameWithOwner
        LEFT JOIN FETCH i.repository
        WHERE TYPE(i) = Issue
          AND i.author.id = :userId
          AND i.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND i.repository.provider.id = :providerId
          AND (i.createdAt >= :since OR i.closedAt >= :since)
        """ + NOT_HIDDEN + "i.repository)" + """
        ORDER BY COALESCE(i.closedAt, i.createdAt) DESC, i.id DESC
        """)
    List<Issue> findAuthoredIssuesActiveSince(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            @Param("since") Instant since,
            Pageable page);

    /** Reviews the developer gave on other people's pull requests since {@code since}, newest first. */
    @Query("""
        SELECT r
        FROM PullRequestReview r
        JOIN FETCH r.pullRequest pr
        LEFT JOIN FETCH pr.author
        LEFT JOIN FETCH pr.repository
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = pr.repository.nameWithOwner
        WHERE r.author.id = :userId
          AND r.pullRequest.deletedAt IS NULL
          AND (pr.author IS NULL OR pr.author.id <> :userId)
          AND r.submittedAt >= :since
          AND rtm.workspace.id = :workspaceId
          AND pr.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "pr.repository)" + """
        ORDER BY r.submittedAt DESC, r.id DESC
        """)
    List<PullRequestReview> findReviewsGivenSince(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            @Param("since") Instant since,
            Pageable page);

    /** Comments the developer wrote on pull requests and issues since {@code since}, newest first. */
    @Query("""
        SELECT c
        FROM IssueComment c
        JOIN FETCH c.issue i
        LEFT JOIN FETCH i.repository
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = i.repository.nameWithOwner
        WHERE c.author.id = :userId
          AND c.issue.deletedAt IS NULL
          AND c.createdAt >= :since
          AND rtm.workspace.id = :workspaceId
          AND i.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "i.repository)" + """
        ORDER BY c.createdAt DESC, c.id DESC
        """)
    List<IssueComment> findCommentsWrittenSince(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            @Param("since") Instant since,
            Pageable page);

    /** Inline review comments the developer wrote since {@code since}, newest first. */
    @Query("""
        SELECT c
        FROM PullRequestReviewComment c
        JOIN FETCH c.pullRequest pr
        LEFT JOIN FETCH pr.repository
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = pr.repository.nameWithOwner
        WHERE c.author.id = :userId
          AND c.pullRequest.deletedAt IS NULL
          AND c.createdAt >= :since
          AND rtm.workspace.id = :workspaceId
          AND pr.repository.provider.id = :providerId
        """ + NOT_HIDDEN + "pr.repository)" + """
        ORDER BY c.createdAt DESC, c.id DESC
        """)
    List<PullRequestReviewComment> findInlineCommentsWrittenSince(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            @Param("since") Instant since,
            Pageable page);

    /** Commits the developer authored since {@code since}, newest first. */
    @Query("""
        SELECT c
        FROM Commit c
        JOIN FETCH c.repository repo
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = repo.nameWithOwner
        WHERE c.author.id = :userId
          AND c.authoredAt >= :since
          AND rtm.workspace.id = :workspaceId
          AND repo.provider.id = :providerId
        """ + NOT_HIDDEN + "repo)" + """
        ORDER BY c.authoredAt DESC, c.id DESC
        """)
    List<Commit> findCommitsAuthoredSince(
            @Param("workspaceId") Long workspaceId,
            @Param("providerId") Long providerId,
            @Param("userId") Long userId,
            @Param("since") Instant since,
            Pageable page);
}
