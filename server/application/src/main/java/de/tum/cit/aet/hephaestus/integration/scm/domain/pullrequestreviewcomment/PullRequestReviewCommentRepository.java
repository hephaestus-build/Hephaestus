package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.RepositoryItemCountProjection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Comments scoped through review_id -> repository.workspace_id")
public interface PullRequestReviewCommentRepository extends JpaRepository<PullRequestReviewComment, Long> {
    Optional<PullRequestReviewComment> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    boolean existsByNativeIdAndProviderId(Long nativeId, Long providerId);

    /**
     * Per-repository review-comment count for the sync-observability breakdown, batched over every
     * repository of a connection in one grouped join. Joins through the direct {@code pullRequest}
     * association rather than {@code review} — a review comment always has the former, while the
     * latter is null for comments that arrive outside a review decision.
     *
     * <p>Comments of a tombstoned pull request are excluded, matching how the pull-request count itself
     * already drops tombstoned rows. A review comment has no tombstone of its own; the parent's is the
     * only signal, and counting orphans of a deleted PR would leave this row permanently inflated. The
     * predicate rides the {@code c.pullRequest} join the grouping already needs.
     */
    @Query("SELECT c.pullRequest.repository.id AS repositoryId, COUNT(c) AS itemCount "
            + "FROM PullRequestReviewComment c "
            + "WHERE c.pullRequest.repository.id IN :repositoryIds AND c.pullRequest.deletedAt IS NULL "
            + "GROUP BY c.pullRequest.repository.id")
    List<RepositoryItemCountProjection> countGroupedByRepositoryIds(
            @Param("repositoryIds") Collection<Long> repositoryIds);

    /**
     * Fetch all review comments for a pull request with their authors eagerly loaded.
     *
     * @param pullRequestId the pull request ID
     * @return comments with author eagerly loaded, ordered by creation time
     */
    @Query("""
        SELECT prrc
        FROM PullRequestReviewComment prrc
        LEFT JOIN FETCH prrc.author
        WHERE prrc.pullRequest.id = :pullRequestId
        ORDER BY prrc.createdAt ASC
        """)
    List<PullRequestReviewComment> findByPullRequestIdWithAuthorOrderByCreatedAt(
            @Param("pullRequestId") Long pullRequestId);

    @Query("SELECT prrc FROM PullRequestReviewComment prrc LEFT JOIN FETCH prrc.author "
            + "WHERE prrc.pullRequest.id = :pullRequestId ORDER BY prrc.createdAt DESC, prrc.id DESC")
    List<PullRequestReviewComment> findRecentByPullRequestIdWithAuthor(
            @Param("pullRequestId") Long pullRequestId, Pageable pageable);

    @Query("""
        SELECT prrc
        FROM PullRequestReviewComment prrc
        LEFT JOIN FETCH prrc.author
        LEFT JOIN FETCH prrc.pullRequest pr
        LEFT JOIN FETCH pr.author
        LEFT JOIN FETCH pr.repository
        WHERE prrc.id IN :ids
        """)
    List<PullRequestReviewComment> findAllByIdWithRelations(@Param("ids") Collection<Long> ids);

    Optional<PullRequestReviewComment> findFirstByThreadIdOrderByCreatedAtAsc(Long threadId);
}
