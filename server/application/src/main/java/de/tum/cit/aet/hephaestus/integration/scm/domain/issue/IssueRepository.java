package de.tum.cit.aet.hephaestus.integration.scm.domain.issue;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.RepositoryItemCountProjection;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * All queries filter by repository ID, which inherently carries scope
 * through the repository -> workspace relationship chain.
 */
@WorkspaceAgnostic("Issues scoped through repository_id -> repository.workspace_id")
public interface IssueRepository extends JpaRepository<Issue, Long> {
    /**
     * Finds an issue (not a pull request) by repository ID and number.
     * Uses {@code TYPE(i) = Issue} to exclude PullRequest subclass rows, which is
     * necessary for GitLab where issues and merge requests have separate IID
     * namespaces (Issue #5 and MR !5 can coexist in the same project).
     *
     * @param repositoryId the repository ID
     * @param number the issue number within the repository
     * @return the issue if found
     */
    @Query("""
        SELECT i
        FROM Issue i
        LEFT JOIN FETCH i.labels
        LEFT JOIN FETCH i.author
        LEFT JOIN FETCH i.assignees
        LEFT JOIN FETCH i.repository
        LEFT JOIN FETCH i.milestone
        WHERE TYPE(i) = Issue AND i.repository.id = :repositoryId AND i.number = :number
        """)
    Optional<Issue> findByRepositoryIdAndNumber(@Param("repositoryId") long repositoryId, @Param("number") int number);

    /**
     * How many issues name {@code parentIssueId} as their parent, and how many of those are closed, as one
     * {@code [total, completed]} row: the rollup a provider that syncs only the parent link never supplies.
     * {@code TYPE(i) = Issue} keeps a pull request out of the count even if one ever carried a parent.
     */
    @Query("SELECT COUNT(i) AS total, COALESCE(SUM(CASE WHEN i.state = :closed THEN 1 ELSE 0 END), 0) AS completed "
            + "FROM Issue i WHERE TYPE(i) = Issue AND i.parentIssue.id = :parentIssueId")
    ChildRollup countChildrenByParentIssueId(
            @Param("parentIssueId") long parentIssueId, @Param("closed") Issue.State closed);

    interface ChildRollup {
        long getTotal();

        long getCompleted();
    }

    /** Fetches an issue with its repository eagerly — used to build an issue-detection job submission. */
    @Query("SELECT i FROM Issue i LEFT JOIN FETCH i.repository WHERE TYPE(i) = Issue AND i.id = :id")
    Optional<Issue> findByIdWithRepository(@Param("id") long id);

    @Query("SELECT i FROM Issue i LEFT JOIN FETCH i.author LEFT JOIN FETCH i.repository "
            + "WHERE TYPE(i) = Issue AND i.id = :id")
    Optional<Issue> findByIdWithAuthorAndRepository(@Param("id") long id);

    /**
     * Fetches an issue with the associations {@code PracticeReviewDetectionGate.evaluateIssue} needs:
     * repository (workspace resolution) and assignees (role check). Restricted to {@code TYPE(i) = Issue}
     * so a pull-request row never enters the issue-detection path.
     *
     * <p>The author comes along because the same load feeds
     * {@code ReviewRequestAuthority}, which asks whether the requester is the person the review is
     * <em>about</em>. Author is a lazy {@code @ManyToOne} and this graph is read outside a session, so
     * without the fetch that question is answered by a {@code LazyInitializationException} — or, worse
     * under a future session, by a null that refuses an issue's own author a review of their own work.
     */
    @Query("SELECT i FROM Issue i LEFT JOIN FETCH i.repository LEFT JOIN FETCH i.author LEFT JOIN FETCH i.assignees "
            + "WHERE TYPE(i) = Issue AND i.id = :id")
    Optional<Issue> findByIdWithRepositoryAndAssignees(@Param("id") long id);

    /**
     * Repository-wide list of pure issues — {@code TYPE(i) = Issue} excludes the {@code PullRequest}
     * subclass rows that share this table under single-table inheritance.
     *
     * <p>Not cosmetic: for GitLab, issue IIDs and merge-request IIDs occupy <em>separate</em>
     * per-project namespaces (issue #5 and MR !5 routinely coexist), so a type-blind list keyed by IID
     * would collide the two — mistaking a merge request for the issue of the same number. Callers here
     * key issues by {@code getNumber()} (the IID) for issue-only concepts (sub-issue parenting,
     * issue-dependency links), so they must never see {@code PullRequest} rows.
     *
     * @param repositoryId the repository ID
     * @return every pure issue of the repository (pull/merge requests excluded)
     */
    @Query("SELECT i FROM Issue i WHERE TYPE(i) = Issue AND i.repository.id = :repositoryId")
    List<Issue> findAllIssuesByRepositoryId(@Param("repositoryId") long repositoryId);

    /**
     * Per-repository count of pure issues — {@code TYPE(i) = Issue} excludes the {@code PullRequest}
     * subclass rows that share this table under single-table inheritance.
     *
     * <p>Split from its pull-request counterpart, rather than one combined count, because the combined
     * number is exactly what makes a stalled entity class invisible: issues and pull requests sync on
     * separate code paths, and one of them stopping keeps the sum rising.
     *
     * <p>Batched over a collection of repository ids: the sync-observability read model this backs
     * renders every repository of a connection on one page load, so a per-repository call would be an
     * N+1 by construction.
     *
     * <p>Tombstoned rows ({@code deletedAt IS NOT NULL} — deleted upstream, kept only so
     * feedback/observation rows referencing them still resolve) are excluded: this number is the
     * admin's answer to "what is in the mirror", and counting rows that no longer exist upstream is
     * exactly the permanent inflation the deletion sweep exists to remove.
     *
     * @param repositoryIds the repository IDs to count for
     * @return one projection row per repository that has at least one live issue
     */
    @Query("SELECT i.repository.id AS repositoryId, COUNT(i) AS itemCount FROM Issue i "
            + "WHERE TYPE(i) = Issue AND i.deletedAt IS NULL AND i.repository.id IN :repositoryIds "
            + "GROUP BY i.repository.id")
    List<RepositoryItemCountProjection> countIssuesGroupedByRepositoryIds(
            @Param("repositoryIds") Collection<Long> repositoryIds);

    /** Per-repository count of live pull/merge requests. Counterpart to {@link #countIssuesGroupedByRepositoryIds}. */
    @Query("SELECT i.repository.id AS repositoryId, COUNT(i) AS itemCount FROM Issue i "
            + "WHERE TYPE(i) = PullRequest AND i.deletedAt IS NULL AND i.repository.id IN :repositoryIds "
            + "GROUP BY i.repository.id")
    List<RepositoryItemCountProjection> countPullRequestsGroupedByRepositoryIds(
            @Param("repositoryIds") Collection<Long> repositoryIds);

    /**
     * Live (non-tombstoned) issue numbers for one repository — the local half of the deletion
     * sweep's set difference. {@code TYPE(i) = Issue} keeps pull requests out; they are swept
     * separately against a different upstream connection.
     *
     * <p>Returns bare numbers rather than entities: the sweep only needs the key set, and a repo
     * with 50k issues would otherwise hydrate 50k entity graphs to compare integers.
     */
    @Query(
            "SELECT i.number FROM Issue i WHERE TYPE(i) = Issue AND i.deletedAt IS NULL AND i.repository.id = :repositoryId")
    List<Integer> findLiveIssueNumbersByRepositoryId(@Param("repositoryId") Long repositoryId);

    /** Live pull-request numbers for one repository. Counterpart to {@link #findLiveIssueNumbersByRepositoryId}. */
    @Query(
            "SELECT i.number FROM Issue i WHERE TYPE(i) = PullRequest AND i.deletedAt IS NULL AND i.repository.id = :repositoryId")
    List<Integer> findLivePullRequestNumbersByRepositoryId(@Param("repositoryId") Long repositoryId);

    /**
     * Tombstones the given <em>issue</em> numbers of one repository in a single statement.
     *
     * <p>{@code TYPE(i) = Issue} excludes the {@code PullRequest} subclass rows sharing this table
     * under single-table inheritance. Not cosmetic: for GitLab, issue and merge-request IIDs occupy
     * <em>separate</em> per-project namespaces (issue #5 and MR !5 coexist), so a type-blind UPDATE
     * keyed only on {@code (repository.id, number)} would tombstone a live merge request whenever an
     * issue of the same number is swept — hiding it from counts, mentor and detection until the next
     * reconciliation upsert revives it.
     *
     * <p>{@code deletedAt IS NULL} in the predicate makes this idempotent and preserves the
     * <em>first</em> observation time: a re-sweep of an already-tombstoned row must not push its
     * timestamp forward, or a future hard-delete retention window would never elapse.
     *
     * @return the number of rows newly tombstoned
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE Issue i SET i.deletedAt = :deletedAt WHERE TYPE(i) = Issue AND i.repository.id = :repositoryId "
            + "AND i.number IN :numbers AND i.deletedAt IS NULL")
    int tombstoneIssuesByRepositoryIdAndNumbers(
            @Param("repositoryId") Long repositoryId,
            @Param("numbers") Collection<Integer> numbers,
            @Param("deletedAt") Instant deletedAt);

    /**
     * Tombstones the given <em>pull-request / merge-request</em> numbers of one repository in a single
     * statement. Counterpart to {@link #tombstoneIssuesByRepositoryIdAndNumbers}; {@code TYPE(i) =
     * PullRequest} keeps the pure-issue rows out of the write for the same separate-namespace reason.
     *
     * @return the number of rows newly tombstoned
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            "UPDATE Issue i SET i.deletedAt = :deletedAt WHERE TYPE(i) = PullRequest AND i.repository.id = :repositoryId "
                    + "AND i.number IN :numbers AND i.deletedAt IS NULL")
    int tombstonePullRequestsByRepositoryIdAndNumbers(
            @Param("repositoryId") Long repositoryId,
            @Param("numbers") Collection<Integer> numbers,
            @Param("deletedAt") Instant deletedAt);

    /**
     * Repository-wide issue inventory (pure issues, PullRequest subclass rows excluded) ordered
     * newest-first by number, for the cross-artifact project-context telescope. The author is fetched
     * up front to avoid a per-row lazy load; labels/comments/bodies are intentionally NOT fetched — the
     * inventory is a compact "what else exists in this project" index, not a full body. Tombstones are
     * excluded: something deleted upstream is no longer in the project.
     *
     * @param repositoryId the repository ID
     * @param pageable the cap (newest N) — caller supplies {@code PageRequest.of(0, cap)}
     * @return newest-first issues for the repository
     */
    @Query("SELECT i FROM Issue i LEFT JOIN FETCH i.author LEFT JOIN FETCH i.milestone "
            + "WHERE TYPE(i) = Issue AND i.repository.id = :repositoryId AND i.deletedAt IS NULL "
            + "ORDER BY i.number DESC")
    List<Issue> findIssueInventoryByRepositoryId(@Param("repositoryId") long repositoryId, Pageable pageable);

    /**
     * How many of a repository's issues carry at least one label, and which label names are in use — the
     * two facts that say whether the project has a labelling convention at all, without fetching the label
     * collection of every inventory row.
     */
    @Query("SELECT COUNT(DISTINCT i.id) FROM Issue i JOIN i.labels l "
            + "WHERE TYPE(i) = Issue AND i.repository.id = :repositoryId AND i.deletedAt IS NULL")
    long countLabelledIssuesByRepositoryId(@Param("repositoryId") long repositoryId);

    @Query(
            "SELECT COUNT(i) FROM Issue i WHERE TYPE(i) = Issue AND i.repository.id = :repositoryId AND i.deletedAt IS NULL")
    long countIssuesByRepositoryId(@Param("repositoryId") long repositoryId);

    @Query("SELECT DISTINCT l.name FROM Issue i JOIN i.labels l "
            + "WHERE TYPE(i) = Issue AND i.repository.id = :repositoryId AND i.deletedAt IS NULL ORDER BY l.name")
    List<String> findLabelNamesInUseByRepositoryId(@Param("repositoryId") long repositoryId);

    /**
     * Nullifies milestone references on all issues that reference the given milestone.
     * <p>
     * This is a direct database update that doesn't rely on Hibernate's collection state.
     * MUST be called before deleting a milestone to avoid foreign key constraint violations.
     * <p>
     * Unlike {@code milestone.removeAllIssues()} which operates on the in-memory collection
     * (which may be stale or not fully loaded), this method updates ALL issues in the
     * database that reference the milestone.
     *
     * @param milestoneId the milestone ID to detach from issues
     * @return the number of issues updated
     */
    @Modifying
    @Query("UPDATE Issue i SET i.milestone = null WHERE i.milestone.id = :milestoneId")
    int clearMilestoneReferences(@Param("milestoneId") @Nullable Long milestoneId);

    /**
     * Finds an issue by ID with its blockedBy collection eagerly loaded.
     * <p>
     * This is needed by dependency sync because the persistence context may have been
     * cleared by prior {@code upsertCore()} calls (via {@code clearAutomatically = true}),
     * which detaches entities and invalidates lazy proxies.
     *
     * @param id the issue ID
     * @return the issue with blockedBy eagerly loaded, if found
     */
    @Query("""
        SELECT i
        FROM Issue i
        LEFT JOIN FETCH i.blockedBy
        WHERE i.id = :id
        """)
    Optional<Issue> findByIdWithBlockedBy(@Param("id") Long id);

    /**
     * Promotes a stub {@code ISSUE} row to {@code PULL_REQUEST} ahead of the upsert. The
     * {@code COALESCE}s seed PR-specific NOT-NULL primitives so the discriminator flip survives
     * the single-table-inheritance CHECK constraint; {@code upsertCore} overwrites them with
     * real values immediately after.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            value = "UPDATE issue SET issue_type = :newType, " + "is_draft = COALESCE(is_draft, false), "
                    + "is_merged = COALESCE(is_merged, false), "
                    + "commits = COALESCE(commits, 0), "
                    + "additions = COALESCE(additions, 0), "
                    + "deletions = COALESCE(deletions, 0), "
                    + "changed_files = COALESCE(changed_files, 0) "
                    + "WHERE repository_id = :repositoryId AND number = :number AND issue_type = :currentType",
            nativeQuery = true)
    int correctDiscriminator(
            @Param("repositoryId") long repositoryId,
            @Param("number") int number,
            @Param("currentType") String currentType,
            @Param("newType") String newType);

    /**
     * Atomically inserts or updates an issue's core fields (race-condition safe).
     * <p>
     * Uses PostgreSQL's ON CONFLICT to handle concurrent inserts on the unique constraint
     * (repository_id, issue_type, number). This eliminates the race condition where two threads
     * both pass the findById check and try to insert the same issue.
     * <p>
     * <b>Note:</b> This only handles scalar fields and FK references. ManyToMany relationships
     * (labels, assignees) must be handled separately after calling this method.
     *
     * @return 1 if inserted, 1 if updated (always 1 on success due to DO UPDATE)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO issue (
            native_id, provider_id, number, title, body, state, state_reason, html_url, is_locked,
            closed_at, comments_count, last_sync_at, created_at, updated_at,
            author_id, repository_id, milestone_id, issue_type_id,
            parent_issue_id, sub_issues_total, sub_issues_completed, sub_issues_percent_completed,
            issue_type
        )
        VALUES (
            :nativeId, :providerId, :number, :title, :body, :state, :stateReason, :htmlUrl,
            COALESCE(:isLocked, false),
            :closedAt, COALESCE(:commentsCount, 0), :lastSyncAt, :createdAt, :updatedAt,
            :authorId, :repositoryId, :milestoneId, :issueTypeId,
            :parentIssueId, :subIssuesTotal, :subIssuesCompleted, :subIssuesPercentCompleted,
            'ISSUE'
        )
        ON CONFLICT (repository_id, issue_type, number) DO UPDATE SET
            title = EXCLUDED.title,
            body = EXCLUDED.body,
            state = EXCLUDED.state,
            state_reason = EXCLUDED.state_reason,
            html_url = EXCLUDED.html_url,
            is_locked = COALESCE(EXCLUDED.is_locked, issue.is_locked),
            closed_at = EXCLUDED.closed_at,
            comments_count = COALESCE(EXCLUDED.comments_count, issue.comments_count),
            last_sync_at = EXCLUDED.last_sync_at,
            updated_at = EXCLUDED.updated_at,
            author_id = COALESCE(EXCLUDED.author_id, issue.author_id),
            milestone_id = EXCLUDED.milestone_id,
            issue_type_id = COALESCE(EXCLUDED.issue_type_id, issue.issue_type_id),
            parent_issue_id = COALESCE(EXCLUDED.parent_issue_id, issue.parent_issue_id),
            sub_issues_total = COALESCE(EXCLUDED.sub_issues_total, issue.sub_issues_total),
            sub_issues_completed = COALESCE(EXCLUDED.sub_issues_completed, issue.sub_issues_completed),
            sub_issues_percent_completed = COALESCE(EXCLUDED.sub_issues_percent_completed, issue.sub_issues_percent_completed),
            -- Resurrect: upstream just handed us this issue, so any tombstone on it is wrong. This is
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
            @Param("issueTypeId") @Nullable String issueTypeId,
            @Param("parentIssueId") @Nullable Long parentIssueId,
            @Param("subIssuesTotal") @Nullable Integer subIssuesTotal,
            @Param("subIssuesCompleted") @Nullable Integer subIssuesCompleted,
            @Param("subIssuesPercentCompleted") @Nullable Integer subIssuesPercentCompleted);
}
