package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.RepositoryItemCountProjection;
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
 */
@Repository
@WorkspaceAgnostic("Reviews scoped through pull_request_id -> repository.workspace_id")
public interface PullRequestReviewRepository extends JpaRepository<PullRequestReview, Long> {
    boolean existsByIdAndPullRequest_IdAndAuthor_Id(long id, long pullRequestId, long authorId);

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

    /** Reviews by id, each with its pull request, that pull request's author and its repository. */
    @Query("""
        SELECT prr
        FROM PullRequestReview prr
        LEFT JOIN FETCH prr.pullRequest pr
        LEFT JOIN FETCH pr.author
        LEFT JOIN FETCH pr.repository
        WHERE prr.id IN :ids
        """)
    List<PullRequestReview> findAllByIdWithRelations(@Param("ids") Collection<Long> ids);
}
