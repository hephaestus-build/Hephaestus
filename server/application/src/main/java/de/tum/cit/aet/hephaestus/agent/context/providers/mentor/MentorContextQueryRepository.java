package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
                WHERE p1.author.id = :userId
                  AND p1.deletedAt IS NULL
                  AND rtm1.workspace.id = :workspaceId
                  AND p1.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN), 0L),
            COALESCE((SELECT COUNT(p2)
                FROM PullRequest p2
                JOIN RepositoryToMonitor rtm2 ON rtm2.nameWithOwner = p2.repository.nameWithOwner
                WHERE p2.author.id = :userId
                  AND p2.deletedAt IS NULL
                  AND rtm2.workspace.id = :workspaceId
                  AND p2.isMerged = true
                  AND p2.mergedAt > :weekAgo
                  AND p2.mergedAt <= :now), 0L),
            COALESCE((SELECT COUNT(p3)
                FROM PullRequest p3
                JOIN RepositoryToMonitor rtm3 ON rtm3.nameWithOwner = p3.repository.nameWithOwner
                WHERE p3.author.id = :userId
                  AND p3.deletedAt IS NULL
                  AND rtm3.workspace.id = :workspaceId
                  AND p3.isMerged = true
                  AND p3.mergedAt > :twoWeeksAgo
                  AND p3.mergedAt <= :weekAgo), 0L),
            COALESCE((SELECT COUNT(i)
                FROM Issue i
                JOIN RepositoryToMonitor rtmi ON rtmi.nameWithOwner = i.repository.nameWithOwner
                WHERE TYPE(i) = Issue
                  AND i.author.id = :userId
                  AND i.deletedAt IS NULL
                  AND rtmi.workspace.id = :workspaceId
                  AND i.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN), 0L),
            COALESCE((SELECT COUNT(r1)
                FROM PullRequestReview r1
                JOIN RepositoryToMonitor rtmr1 ON rtmr1.nameWithOwner = r1.pullRequest.repository.nameWithOwner
                WHERE r1.author.id = :userId
                  AND r1.pullRequest.deletedAt IS NULL
                  AND rtmr1.workspace.id = :workspaceId
                  AND r1.submittedAt > :weekAgo
                  AND r1.submittedAt <= :now), 0L),
            COALESCE((SELECT COUNT(r2)
                FROM PullRequestReview r2
                JOIN RepositoryToMonitor rtmr2 ON rtmr2.nameWithOwner = r2.pullRequest.repository.nameWithOwner
                WHERE r2.author.id = :userId
                  AND r2.pullRequest.deletedAt IS NULL
                  AND rtmr2.workspace.id = :workspaceId
                  AND r2.submittedAt > :twoWeeksAgo
                  AND r2.submittedAt <= :weekAgo), 0L),
            COALESCE((SELECT COUNT(r3)
                FROM PullRequestReview r3
                JOIN RepositoryToMonitor rtmr3 ON rtmr3.nameWithOwner = r3.pullRequest.repository.nameWithOwner
                WHERE r3.pullRequest.author.id = :userId
                  AND r3.pullRequest.deletedAt IS NULL
                  AND rtmr3.workspace.id = :workspaceId
                  AND r3.submittedAt > :weekAgo
                  AND r3.submittedAt <= :now), 0L),
            COALESCE((SELECT COUNT(prr)
                FROM PullRequest prr
                JOIN RepositoryToMonitor rtmpr ON rtmpr.nameWithOwner = prr.repository.nameWithOwner
                JOIN prr.requestedReviewers reviewer
                WHERE reviewer.id = :userId
                  AND prr.deletedAt IS NULL
                  AND rtmpr.workspace.id = :workspaceId
                  AND prr.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN), 0L),
            COALESCE((SELECT COUNT(t)
                FROM PullRequestReviewThread t
                JOIN RepositoryToMonitor rtmt ON rtmt.nameWithOwner = t.pullRequest.repository.nameWithOwner
                WHERE t.pullRequest.author.id = :userId
                  AND t.pullRequest.deletedAt IS NULL
                  AND rtmt.workspace.id = :workspaceId
                  AND t.pullRequest.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
                  AND t.state = de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread.State.UNRESOLVED), 0L)
        )
        FROM User u
        WHERE u.id = :userId
        """)
    MentorUserCounts fetchUserCounts(
            @Param("workspaceId") Long workspaceId,
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
        JOIN i.assignees a
        LEFT JOIN FETCH i.repository
        LEFT JOIN FETCH i.milestone
        WHERE TYPE(i) = Issue
          AND a.id = :userId
          AND i.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND i.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
        ORDER BY i.createdAt DESC
        """)
    List<Issue> findAssignedOpenIssues(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    /** Open PRs where user has been requested to review, with author + repo fetched. */
    @Query("""
        SELECT p
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        JOIN p.requestedReviewers reviewer
        LEFT JOIN FETCH p.author
        LEFT JOIN FETCH p.repository
        WHERE reviewer.id = :userId
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND p.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
        ORDER BY p.createdAt DESC
        """)
    List<PullRequest> findPendingReviewRequestPrs(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    /** Recent chat threads for the user within the workspace, newest first. Caller passes
     *  a {@link Pageable} ({@code PageRequest.of(0, limit)}) so the DB-side LIMIT keeps power
     *  users (hundreds of threads) from hydrating the full list just to {@code subList(0, 10)}. */
    @Query("""
        SELECT t
        FROM ChatThread t
        WHERE t.workspace.id = :workspaceId
          AND t.user.id = :userId
        ORDER BY t.createdAt DESC
        """)
    List<ChatThread> findRecentChatThreads(
            @Param("workspaceId") Long workspaceId, @Param("userId") Long userId, Pageable pageable);

    // Observations context — reviews received in window

    /**
     * Earliest USER-role message per thread for a batch of thread ids, in one round trip. Native because
     * Postgres {@code DISTINCT ON} is the cheapest path; equivalent JPQL would need a correlated
     * subquery for every thread anyway. Joins {@code chat_thread} on {@code workspace_id} so the
     * query refuses cross-tenant ids the caller might pass — defence in depth even though the
     * upstream id list is already user-scoped.
     *
     * <p>{@code m.parts::text} casts the {@code jsonb} column to text so the projection element is a plain
     * String the caller parses with {@code readTree}. Selecting the raw {@code jsonb} into an untyped
     * {@code Object[]} makes Hibernate apply its JSON Java-type and throw "JSON deserialize failed for String
     * … from Array", silently degrading the prior-conversation context to empty.
     *
     * <p><b>Precondition:</b> {@code threadIds} MUST be non-empty. The native {@code IN (:threadIds)}
     * expands to invalid {@code IN ()} SQL (a Postgres syntax error) for an empty list, so callers must
     * short-circuit beforehand (as {@code WorkspaceContentSource.loadFirstUserMessages} does).
     */
    @Query(value = """
        SELECT DISTINCT ON (m.thread_id) m.thread_id, m.parts::text
          FROM chat_message m
          JOIN chat_thread t ON t.id = m.thread_id
         WHERE m.thread_id IN (:threadIds)
           AND t.workspace_id = :workspaceId
           AND m.role = 'USER'
         ORDER BY m.thread_id, m.created_at ASC
        """, nativeQuery = true)
    List<Object[]> findFirstUserMessagePartsByThreadIds(
            @Param("workspaceId") Long workspaceId, @Param("threadIds") List<UUID> threadIds);

    /**
     * Reviews received on user's authored PRs within {@code since}..now, scoped to workspace.
     * Page the cap into the DB query — a heavy reviewer's 90-day window can return hundreds of
     * rows; surfacing more than {@code MAX_RECENT_REVIEWS} is wasted work.
     */
    @Query("""
        SELECT prr
        FROM PullRequestReview prr
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = prr.pullRequest.repository.nameWithOwner
        LEFT JOIN FETCH prr.author
        LEFT JOIN FETCH prr.pullRequest
        WHERE prr.pullRequest.author.id = :userId
          AND prr.pullRequest.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND prr.submittedAt > :since
        ORDER BY prr.submittedAt DESC
        """)
    List<PullRequestReview> findReviewsReceivedSince(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("since") Instant since,
            Pageable page);

    /**
     * The developer's own authored PULL REQUESTS in the workspace, newest first — the work itself (not
     * observations about it), so the mentor has a concrete, linkable inventory of what they shipped. Same
     * author + RepositoryToMonitor scoping as {@link #findReviewsReceivedSince}. {@code FROM PullRequest}
     * narrows to the PR discriminator (single-table inheritance), so issues never leak in.
     */
    @Query("""
        SELECT p
        FROM PullRequest p
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = p.repository.nameWithOwner
        WHERE p.author.id = :userId
          AND p.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
        ORDER BY p.createdAt DESC
        """)
    List<PullRequest> findRecentAuthoredPullRequests(
            @Param("workspaceId") Long workspaceId, @Param("userId") Long userId, Pageable page);

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
        WHERE p.author.id = :userId
          AND p.deletedAt IS NULL
          AND p.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
          AND rtm.workspace.id = :workspaceId
          AND r.provider.id = :providerId
        ORDER BY p.updatedAt DESC NULLS LAST, p.id DESC
        """)
    List<PullRequest> findOpenAuthoredPullRequestsOnInstance(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("providerId") Long providerId,
            Pageable page);

    /** One pull request under the same scope as {@link #findOpenAuthoredPullRequestsOnInstance}. */
    @Query("""
        SELECT p
        FROM PullRequest p
        JOIN p.repository r
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = r.nameWithOwner
        WHERE p.id = :pullRequestId
          AND p.author.id = :userId
          AND p.deletedAt IS NULL
          AND p.state = de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State.OPEN
          AND rtm.workspace.id = :workspaceId
          AND r.provider.id = :providerId
        """)
    List<PullRequest> findOpenAuthoredPullRequestOnInstance(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("providerId") Long providerId,
            @Param("pullRequestId") Long pullRequestId);

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
     * newest first. Companion to {@link #findRecentAuthoredPullRequests}.
     */
    @Query("""
        SELECT i
        FROM Issue i
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = i.repository.nameWithOwner
        WHERE i.author.id = :userId
          AND i.deletedAt IS NULL
          AND rtm.workspace.id = :workspaceId
          AND TYPE(i) = Issue
        ORDER BY i.createdAt DESC
        """)
    List<Issue> findRecentAuthoredIssues(
            @Param("workspaceId") Long workspaceId, @Param("userId") Long userId, Pageable page);
}
