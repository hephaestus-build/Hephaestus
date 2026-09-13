package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds only domain-agnostic queries for the integration.scm domain. Scope-filtered queries (those that
 * join with RepositoryToMonitor or other consuming-module entities) belong in the consuming packages
 * (leaderboard, profile, practices, ...) to keep architecture boundaries clean.
 *
 * @see de.tum.cit.aet.hephaestus.profile.ProfilePullRequestQueryRepository
 */
@Repository
@WorkspaceAgnostic("Pull requests scoped through repository_id -> repository.workspace_id")
public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {
    boolean existsByIdAndDeletedAtIsNull(Long id);

    @Query("""
        SELECT p
        FROM PullRequest p
        LEFT JOIN FETCH p.author
        LEFT JOIN FETCH p.repository
        LEFT JOIN FETCH p.milestone
        WHERE p.repository.id = :repositoryId AND p.number = :number
        """)
    Optional<PullRequest> findByRepositoryIdAndNumber(
            @Param("repositoryId") long repositoryId, @Param("number") int number);

    /** Pull request by id with assignees eagerly fetched, for access after the Hibernate session closes. */
    @Query("""
        SELECT p FROM PullRequest p
        LEFT JOIN FETCH p.assignees
        WHERE p.id = :id
        """)
    Optional<PullRequest> findByIdWithAssignees(@Param("id") Long id);

    /** Pull request by id with assignees and repository eagerly fetched, for access outside the original session (async listeners, scheduled tasks). */
    @Query("""
        SELECT p FROM PullRequest p
        LEFT JOIN FETCH p.assignees
        LEFT JOIN FETCH p.repository
        WHERE p.id = :id
        """)
    Optional<PullRequest> findByIdWithAssigneesAndRepository(@Param("id") Long id);

    /** Pull request by id with repository eagerly fetched, for passing across transaction boundaries (e.g. logging nameWithOwner). */
    @Query("""
        SELECT p FROM PullRequest p
        LEFT JOIN FETCH p.repository
        WHERE p.id = :id
        """)
    Optional<PullRequest> findByIdWithRepository(@Param("id") Long id);

    @Query("""
        SELECT p FROM PullRequest p
        LEFT JOIN FETCH p.author
        LEFT JOIN FETCH p.repository
        WHERE p.id = :id
        """)
    Optional<PullRequest> findByIdWithAuthorAndRepository(@Param("id") Long id);

    /**
     * Finds a pull request by ID with all associations needed by the practice review detection gate.
     * <p>
     * Eagerly fetches labels, assignees, repository, author, and mergedBy in a single query.
     * The gate requires labels, assignees (step 6: assignee gate),
     * and repository (step 3: workspace resolution). Author and mergedBy are needed by
     * {@link de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload.PullRequestData#from(PullRequest)}
     * for the ReviewSubmitted path.
     * <p>
     * Uses {@code DISTINCT} because the Cartesian product of two {@code @ManyToMany} collections
     * (labels × assignees) produces duplicate root rows that Hibernate de-duplicates.
     *
     * @param id the pull request ID
     * @return the pull request with all gate associations loaded, or empty if not found
     */
    @Query("""
        SELECT DISTINCT p FROM PullRequest p
        LEFT JOIN FETCH p.labels
        LEFT JOIN FETCH p.assignees
        LEFT JOIN FETCH p.repository
        LEFT JOIN FETCH p.author
        LEFT JOIN FETCH p.mergedBy
        WHERE p.id = :id
        """)
    Optional<PullRequest> findByIdWithAllForGate(@Param("id") Long id);

    /**
     * The head commit alone, for keying a signal without paying for the gate's fetch graph — a
     * reconciliation pass records what it saw and never looks at the rest of the pull request.
     */
    @Query("SELECT p.headRefOid FROM PullRequest p WHERE p.id = :id")
    Optional<String> findHeadRefOidById(@Param("id") Long id);

    List<PullRequest> findAllByRepository_Id(Long repositoryId);

    /**
     * Repository-wide pull-request inventory ordered newest-first by number, for the cross-artifact
     * project-context telescope. The author is fetched up front to avoid a per-row lazy load; review
     * threads/diffs/bodies are intentionally NOT fetched — the inventory is a compact "what else exists
     * in this project" index, not a full body. Tombstones are excluded: something deleted upstream is no
     * longer in the project.
     *
     * @param repositoryId the repository ID
     * @param pageable the cap (newest N) — caller supplies {@code PageRequest.of(0, cap)}
     * @return newest-first pull requests for the repository
     */
    @Query("SELECT p FROM PullRequest p LEFT JOIN FETCH p.author LEFT JOIN FETCH p.milestone "
            + "WHERE p.repository.id = :repositoryId AND p.deletedAt IS NULL ORDER BY p.number DESC")
    List<PullRequest> findPullRequestInventoryByRepositoryId(
            @Param("repositoryId") long repositoryId, Pageable pageable);

    /**
     * Streams all pull requests belonging to a repository. Must be consumed within a try-with-resources
     * block so the stream closes and releases its connection, and the caller must be annotated
     * {@code @Transactional(readOnly = true)} for streaming to work.
     */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "50"))
    Stream<PullRequest> streamAllByRepository_Id(Long repositoryId);

    /**
     * Atomically inserts or updates a pull request's core fields (race-condition safe).
     * <p>
     * Uses PostgreSQL's ON CONFLICT to handle concurrent inserts on the unique constraint
     * (repository_id, issue_type, number). This eliminates the race condition where two threads
     * both pass the findById check and try to insert the same pull request.
     * <p>
     * <b>Note:</b> This only handles scalar fields and FK references. ManyToMany relationships
     * (labels, assignees, requestedReviewers) must be handled separately after calling this method.
     *
     * @return 1 if inserted, 1 if updated (always 1 on success due to DO UPDATE)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO issue (
            native_id, provider_id, number, title, body, state, state_reason, html_url, is_locked,
            closed_at, comments_count, last_sync_at, created_at, updated_at,
            author_id, repository_id, milestone_id,
            merged_at, is_draft, is_merged, commits, additions, deletions, changed_files,
            review_decision, merge_state_status, mergeable,
            head_ref_name, base_ref_name, head_ref_oid, base_ref_oid, merged_by_id,
            merge_commit_sha,
            issue_type
        )
        VALUES (
            :nativeId, :providerId, :number, :title, :body, :state, :stateReason, :htmlUrl,
            COALESCE(:isLocked, false),
            :closedAt, COALESCE(:commentsCount, 0), :lastSyncAt, :createdAt, :updatedAt,
            :authorId, :repositoryId, :milestoneId,
            :mergedAt, :isDraft, :isMerged,
            COALESCE(:commits, 0), COALESCE(:additions, 0), COALESCE(:deletions, 0), COALESCE(:changedFiles, 0),
            :reviewDecision, :mergeStateStatus, :mergeable,
            :headRefName, :baseRefName, :headRefOid, :baseRefOid, :mergedById,
            :mergeCommitSha,
            'PULL_REQUEST'
        )
        ON CONFLICT (repository_id, issue_type, number) DO UPDATE SET
            title = EXCLUDED.title,
            body = EXCLUDED.body,
            state = EXCLUDED.state,
            state_reason = EXCLUDED.state_reason,
            html_url = COALESCE(EXCLUDED.html_url, issue.html_url),
            is_locked = COALESCE(EXCLUDED.is_locked, issue.is_locked),
            closed_at = EXCLUDED.closed_at,
            comments_count = COALESCE(EXCLUDED.comments_count, issue.comments_count),
            last_sync_at = EXCLUDED.last_sync_at,
            updated_at = EXCLUDED.updated_at,
            author_id = COALESCE(EXCLUDED.author_id, issue.author_id),
            milestone_id = EXCLUDED.milestone_id,
            merged_at = EXCLUDED.merged_at,
            is_draft = EXCLUDED.is_draft,
            is_merged = EXCLUDED.is_merged,
            commits = COALESCE(EXCLUDED.commits, issue.commits),
            additions = COALESCE(EXCLUDED.additions, issue.additions),
            deletions = COALESCE(EXCLUDED.deletions, issue.deletions),
            changed_files = COALESCE(EXCLUDED.changed_files, issue.changed_files),
            review_decision = COALESCE(EXCLUDED.review_decision, issue.review_decision),
            merge_state_status = COALESCE(EXCLUDED.merge_state_status, issue.merge_state_status),
            mergeable = COALESCE(EXCLUDED.mergeable, issue.mergeable),
            head_ref_name = COALESCE(EXCLUDED.head_ref_name, issue.head_ref_name),
            base_ref_name = COALESCE(EXCLUDED.base_ref_name, issue.base_ref_name),
            head_ref_oid = COALESCE(EXCLUDED.head_ref_oid, issue.head_ref_oid),
            base_ref_oid = COALESCE(EXCLUDED.base_ref_oid, issue.base_ref_oid),
            merged_by_id = COALESCE(EXCLUDED.merged_by_id, issue.merged_by_id),
            merge_commit_sha = COALESCE(EXCLUDED.merge_commit_sha, issue.merge_commit_sha),
            -- Resurrect: upstream just handed us this PR, so any tombstone on it is wrong. This is
            -- what makes a deletion-sweep tombstone reversible — a sweep that tombstoned on bad
            -- data self-heals on the next ordinary sync instead of needing an operator.
            -- Keep this comment free of the apostrophe character: Hibernate reads one as the start of
            -- a quoted range it never finds the end of, and the whole ApplicationContext fails to
            -- start. NativeQueryCommentArchTest enforces this.
            deleted_at = NULL
        """, nativeQuery = true)
    int upsertCore(
            @Param("nativeId") Long nativeId,
            @Param("providerId") Long providerId,
            @Param("number") int number,
            @Param("title") String title,
            @Param("body") @Nullable String body,
            @Param("state") String state,
            @Param("stateReason") @Nullable String stateReason,
            @Param("htmlUrl") @Nullable String htmlUrl,
            @Param("isLocked") @Nullable Boolean isLocked,
            @Param("closedAt") @Nullable Instant closedAt,
            @Param("commentsCount") @Nullable Integer commentsCount,
            @Param("lastSyncAt") Instant lastSyncAt,
            @Param("createdAt") @Nullable Instant createdAt,
            @Param("updatedAt") @Nullable Instant updatedAt,
            @Param("authorId") @Nullable Long authorId,
            @Param("repositoryId") Long repositoryId,
            @Param("milestoneId") @Nullable Long milestoneId,
            @Param("mergedAt") @Nullable Instant mergedAt,
            @Param("isDraft") boolean isDraft,
            @Param("isMerged") boolean isMerged,
            @Param("commits") @Nullable Integer commits,
            @Param("additions") @Nullable Integer additions,
            @Param("deletions") @Nullable Integer deletions,
            @Param("changedFiles") @Nullable Integer changedFiles,
            @Param("reviewDecision") @Nullable String reviewDecision,
            @Param("mergeStateStatus") @Nullable String mergeStateStatus,
            @Param("mergeable") @Nullable Boolean mergeable,
            @Param("headRefName") @Nullable String headRefName,
            @Param("baseRefName") @Nullable String baseRefName,
            @Param("headRefOid") @Nullable String headRefOid,
            @Param("baseRefOid") @Nullable String baseRefOid,
            @Param("mergedById") @Nullable Long mergedById,
            @Param("mergeCommitSha") @Nullable String mergeCommitSha);
}
