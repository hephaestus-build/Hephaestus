package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.RepositoryItemCountProjection;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Holds domain-agnostic queries for the integration.scm domain. Scope-filtered queries (those that join
 * with host-application entities) belong in the host application to keep architecture boundaries clean.
 *
 * <p>Workspace-agnostic by design: reviews are scoped through
 * {@code pull_request_id -> pull_request.repository_id -> repository.workspace_id}.
 * Provider-domain lookups (by native ID + provider ID, by pull-request ID) run during
 * sync flows; workspace context is established by the caller.
 *
 * @see de.tum.cit.aet.hephaestus.leaderboard.LeaderboardReviewQueryRepository
 */
@Repository
@WorkspaceAgnostic("Reviews scoped through pull_request_id -> repository.workspace_id")
public interface PullRequestReviewRepository extends JpaRepository<PullRequestReview, Long> {
    Optional<PullRequestReview> findByIdAndPullRequestId(Long id, Long pullRequestId);

    Optional<PullRequestReview> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    List<PullRequestReview> findAllByPullRequestIdAndProviderId(Long pullRequestId, Long providerId);

    /**
     * Per-repository review count for the sync-observability breakdown, batched over every repository of
     * a connection in one grouped join. Reviews arrive nested inside the pull-request backfill's GraphQL
     * pages, so this count stalling while the pull-request count keeps rising is a real and otherwise
     * silent failure — which is the reason this class gets its own row.
     *
     * <p>Reviews of a tombstoned pull request are excluded, matching how the pull-request count itself
     * already drops tombstoned rows. A review has no tombstone of its own; the parent's is the only
     * signal, and counting orphans of a deleted PR would leave this row permanently inflated. The
     * predicate rides the {@code r.pullRequest} join the grouping already needs.
     */
    @Query("SELECT r.pullRequest.repository.id AS repositoryId, COUNT(r) AS itemCount FROM PullRequestReview r "
            + "WHERE r.pullRequest.repository.id IN :repositoryIds AND r.pullRequest.deletedAt IS NULL "
            + "GROUP BY r.pullRequest.repository.id")
    List<RepositoryItemCountProjection> countGroupedByRepositoryIds(
            @Param("repositoryIds") Collection<Long> repositoryIds);

    /**
     * All review DECISIONS for a pull request, with the review author eagerly fetched.
     *
     * <p>Used by the cross-context {@code ReviewThreadContentSource} to surface review-decision
     * state (CHANGES_REQUESTED / APPROVED) — the signal a "merged past unresolved request-changes"
     * lesson is grounded in, which neither inline comments nor the diff carry. Read-only context
     * materialisation; the caller establishes workspace scope.
     *
     * <p>Ordered most-recent-first ({@code submittedAt DESC, id DESC}) so the consumer's
     * {@code MAX_DECISIONS} truncation keeps the LATEST decisions: on a heavily-reviewed PR a
     * superseding final APPROVE must not be dropped, which would manufacture a false "merged past
     * unresolved request-changes" finding. {@code id} breaks ties on equal/null timestamps.
     */
    @Query("""
        SELECT prr
        FROM PullRequestReview prr
        LEFT JOIN FETCH prr.author
        WHERE prr.pullRequest.id = :pullRequestId
        ORDER BY prr.submittedAt DESC, prr.id DESC
        """)
    List<PullRequestReview> findAllByPullRequestIdWithAuthor(@Param("pullRequestId") Long pullRequestId);

    @Query("SELECT prr FROM PullRequestReview prr LEFT JOIN FETCH prr.author "
            + "WHERE prr.pullRequest.id = :pullRequestId AND prr.state NOT IN :excludedStates "
            + "ORDER BY prr.submittedAt DESC, prr.id DESC")
    List<PullRequestReview> findRecentByPullRequestIdWithAuthor(
            @Param("pullRequestId") Long pullRequestId,
            @Param("excludedStates") Collection<PullRequestReview.State> excludedStates,
            Pageable pageable);

    /**
     * Batch fetch reviews by IDs with all related entities eagerly loaded.
     *
     * <p>Used by the profile module to hydrate ActivityEvent target entities.
     * Fetches author, pullRequest, repository, and comments in one query to avoid N+1.
     *
     * @param ids the review IDs to fetch
     * @return reviews with related entities eagerly loaded
     */
    @Query("""
        SELECT prr
        FROM PullRequestReview prr
        LEFT JOIN FETCH prr.author
        LEFT JOIN FETCH prr.pullRequest pr
        LEFT JOIN FETCH pr.repository
        LEFT JOIN FETCH prr.comments
        WHERE prr.id IN :ids
        """)
    List<PullRequestReview> findAllByIdWithRelations(@Param("ids") Collection<Long> ids);

    /**
     * Fetch a single review with its pull request and PR author eagerly loaded.
     *
     * <p>Used by the achievement evaluator to check PR authorship without N+1 lazy loads.
     *
     * @param id the review ID
     * @return review with pullRequest and pullRequest.author eagerly loaded
     */
    @Query("""
        SELECT prr
        FROM PullRequestReview prr
        LEFT JOIN FETCH prr.pullRequest pr
        LEFT JOIN FETCH pr.author
        WHERE prr.id = :id
        """)
    Optional<PullRequestReview> findByIdWithPullRequestAuthor(@Param("id") Long id);

    /**
     * Find all reviews by a specific author within a time range, scoped to a workspace.
     *
     * <p>Used by the profile module to show all review activity directly from the source,
     * independent of ActivityEvent records.
     *
     * @param authorLogin the login of the review author
     * @param after start of time range (inclusive)
     * @param before end of time range (exclusive)
     * @param workspaceId the workspace to scope the query to
     * @return reviews with related entities eagerly loaded, ordered by submittedAt descending
     */
    @Query("""
        SELECT prr
        FROM PullRequestReview prr
        LEFT JOIN FETCH prr.author
        LEFT JOIN FETCH prr.pullRequest pr
        LEFT JOIN FETCH pr.repository repo
        LEFT JOIN FETCH prr.comments
        JOIN RepositoryToMonitor rtm ON rtm.nameWithOwner = repo.nameWithOwner
        WHERE prr.author.login = :authorLogin
            AND prr.submittedAt >= :after
            AND prr.submittedAt < :before
            AND prr.author.type = 'USER'
            AND rtm.workspace.id = :workspaceId
        ORDER BY prr.submittedAt DESC
        """)
    List<PullRequestReview> findAllByAuthorLoginInTimeframe(
            @Param("authorLogin") String authorLogin,
            @Param("after") Instant after,
            @Param("before") Instant before,
            @Param("workspaceId") Long workspaceId);
}
