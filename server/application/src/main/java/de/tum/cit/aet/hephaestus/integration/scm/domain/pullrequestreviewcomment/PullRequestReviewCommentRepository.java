package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NoteIdProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.RepositoryItemCountProjection;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("SCM mirror rows are shared across workspaces; callers validate the provider or monitored scope")
public interface PullRequestReviewCommentRepository extends JpaRepository<PullRequestReviewComment, Long> {
    @Query(
            "SELECT c.id AS id, c.nativeId AS nativeId FROM PullRequestReviewComment c WHERE c.pullRequest.id = :parentId")
    List<NoteIdProjection> findNoteIdsByParentId(@Param("parentId") long parentId);

    @WorkspaceAgnostic(
            "GitLab note reconciliation clears or deletes what the provider no longer lists, under one shared parent it syncs")
    @Modifying
    @Query(
            "UPDATE PullRequestReviewComment c SET c.inReplyTo = NULL WHERE c.pullRequest.id = :parentId AND c.inReplyTo.id IN :ids")
    int clearDeletedReplies(@Param("parentId") long parentId, @Param("ids") Collection<Long> ids);

    @WorkspaceAgnostic(
            "GitLab note reconciliation clears or deletes what the provider no longer lists, under one shared parent it syncs")
    @Modifying
    @Query("DELETE FROM PullRequestReviewComment c WHERE c.pullRequest.id = :parentId AND c.id IN :ids")
    int deleteReconciled(@Param("parentId") long parentId, @Param("ids") Collection<Long> ids);

    Optional<PullRequestReviewComment> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    /** Locks the comment, so a compare with its stored {@code updated_at} and the write after it are atomic. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM PullRequestReviewComment c WHERE c.nativeId = :nativeId AND c.provider.id = :providerId")
    Optional<PullRequestReviewComment> findForUpdateByNativeIdAndProviderId(
            @Param("nativeId") Long nativeId, @Param("providerId") Long providerId);

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
    @WorkspaceAgnostic(
            "Counts the shared mirror rows of repositories that the caller resolved through the workspace's monitors")
    @Query("SELECT c.pullRequest.repository.id AS repositoryId, COUNT(c) AS itemCount "
            + "FROM PullRequestReviewComment c "
            + "WHERE c.pullRequest.repository.id IN :repositoryIds AND c.pullRequest.deletedAt IS NULL "
            + "GROUP BY c.pullRequest.repository.id")
    List<RepositoryItemCountProjection> countGroupedByRepositoryIds(
            @Param("repositoryIds") Collection<Long> repositoryIds);

    /** The newest comments with their author, thread and parent. The caller applies delivery provenance. */
    @Query("SELECT prrc FROM PullRequestReviewComment prrc LEFT JOIN FETCH prrc.author "
            + "LEFT JOIN FETCH prrc.thread LEFT JOIN FETCH prrc.inReplyTo "
            + "WHERE prrc.pullRequest.id = :pullRequestId "
            + "ORDER BY prrc.createdAt DESC, prrc.id DESC")
    List<PullRequestReviewComment> findRecentWithContextByPullRequestId(
            @Param("pullRequestId") Long pullRequestId, Pageable pageable);

    Optional<PullRequestReviewComment> findFirstByThreadIdOrderByCreatedAtAsc(Long threadId);
}
