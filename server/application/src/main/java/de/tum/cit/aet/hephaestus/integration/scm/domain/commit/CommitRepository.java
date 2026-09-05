package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

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
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace-agnostic: commits are scoped through {@code repository_id} (a
 * {@link de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository} belongs to exactly
 * one workspace), not via a direct {@code workspace_id} column. Every custom query takes a
 * {@code repositoryId} parameter, except {@link #findByAssociatedPullRequestId}, which scopes through
 * the pull request id (a pull request belongs to exactly one repository); Hibernate-emitted entity
 * load/save SQL is allowed by the PK-only DML carve-out in {@code WorkspaceStatementInspector}.
 */
@Repository
@WorkspaceAgnostic("Commits scoped through repository_id -> repository.workspace_id")
public interface CommitRepository extends JpaRepository<Commit, Long> {
    Optional<Commit> findByShaAndRepositoryId(String sha, Long repositoryId);

    /**
     * Per-repository commit count for the sync-observability breakdown, batched over every repository of
     * a connection in one grouped query. No join needed — a commit carries {@code repository_id}
     * directly, and {@code idx_git_commit_repository_id} already covers the grouping.
     */
    @Query("SELECT c.repository.id AS repositoryId, COUNT(c) AS itemCount FROM Commit c "
            + "WHERE c.repository.id IN :repositoryIds GROUP BY c.repository.id")
    List<RepositoryItemCountProjection> countGroupedByRepositoryIds(
            @Param("repositoryIds") Collection<Long> repositoryIds);

    boolean existsByShaAndRepositoryId(String sha, Long repositoryId);

    long countByRepositoryId(Long repositoryId);

    /** Most recent commit for a repository by authored date. */
    @Query("""
        SELECT c FROM Commit c
        WHERE c.repository.id = :repositoryId
        ORDER BY c.authoredAt DESC
        LIMIT 1
        """)
    Optional<Commit> findLatestByRepositoryId(@Param("repositoryId") Long repositoryId);

    void deleteByRepositoryId(Long repositoryId);

    /** Distinct author emails on commits with no linked user yet ({@code author_id IS NULL}). */
    @Query(value = """
        SELECT DISTINCT gc.author_email FROM git_commit gc
        WHERE gc.repository_id = :repositoryId AND gc.author_id IS NULL AND gc.author_email IS NOT NULL
        """, nativeQuery = true)
    List<String> findDistinctUnresolvedAuthorEmailsByRepositoryId(@Param("repositoryId") Long repositoryId);

    /** Distinct committer emails on commits with no linked user yet ({@code committer_id IS NULL}). */
    @Query(value = """
        SELECT DISTINCT gc.committer_email FROM git_commit gc
        WHERE gc.repository_id = :repositoryId AND gc.committer_id IS NULL AND gc.committer_email IS NOT NULL
        """, nativeQuery = true)
    List<String> findDistinctUnresolvedCommitterEmailsByRepositoryId(@Param("repositoryId") Long repositoryId);

    /** Sets {@code author_id} only where still NULL, so a concurrent resolver's write is never clobbered. */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE git_commit SET author_id = :authorId, updated_at = NOW()
        WHERE repository_id = :repositoryId AND author_id IS NULL AND author_email = :email
        """, nativeQuery = true)
    int bulkUpdateAuthorIdByEmail(
            @Param("email") String email,
            @Param("repositoryId") Long repositoryId,
            @Param("authorId") @Nullable Long authorId);

    /** Sets {@code committer_id} only where still NULL, so a concurrent resolver's write is never clobbered. */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE git_commit SET committer_id = :committerId, updated_at = NOW()
        WHERE repository_id = :repositoryId AND committer_id IS NULL AND committer_email = :email
        """, nativeQuery = true)
    int bulkUpdateCommitterIdByEmail(
            @Param("email") String email,
            @Param("repositoryId") Long repositoryId,
            @Param("committerId") @Nullable Long committerId);

    /**
     * Find one representative SHA per unresolved email for GraphQL author lookup.
     * Returns pairs of (email, sha) where author_id or committer_id is NULL.
     * Uses DISTINCT ON to pick exactly one SHA per email efficiently.
     */
    @Query(value = """
        SELECT sub.email, sub.sha FROM (
            SELECT DISTINCT ON (gc.author_email)
                gc.author_email AS email, gc.sha AS sha
            FROM git_commit gc
            WHERE gc.repository_id = :repositoryId
              AND gc.author_id IS NULL
              AND gc.author_email IS NOT NULL
            UNION ALL
            SELECT DISTINCT ON (gc.committer_email)
                gc.committer_email AS email, gc.sha AS sha
            FROM git_commit gc
            WHERE gc.repository_id = :repositoryId
              AND gc.committer_id IS NULL
              AND gc.committer_email IS NOT NULL
        ) sub
        """, nativeQuery = true)
    List<Object[]> findRepresentativeShasByUnresolvedEmail(@Param("repositoryId") Long repositoryId);

    @Query(value = """
        SELECT gc.sha FROM git_commit gc
        WHERE gc.repository_id = :repositoryId AND gc.author_id IS NULL
        """, nativeQuery = true)
    List<String> findShasWithNullAuthorByRepositoryId(@Param("repositoryId") Long repositoryId);

    @Query(value = """
        SELECT gc.sha FROM git_commit gc
        WHERE gc.repository_id = :repositoryId AND gc.committer_id IS NULL
        """, nativeQuery = true)
    List<String> findShasWithNullCommitterByRepositoryId(@Param("repositoryId") Long repositoryId);

    /** Sets {@code author_id} only where still NULL, so a concurrent resolver's write is never clobbered. */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE git_commit SET author_id = :authorId, updated_at = NOW()
        WHERE repository_id = :repositoryId AND author_id IS NULL AND sha IN (:shas)
        """, nativeQuery = true)
    int bulkUpdateAuthorId(
            @Param("shas") List<String> shas,
            @Param("repositoryId") Long repositoryId,
            @Param("authorId") @Nullable Long authorId);

    /** Sets {@code committer_id} only where still NULL, so a concurrent resolver's write is never clobbered. */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE git_commit SET committer_id = :committerId, updated_at = NOW()
        WHERE repository_id = :repositoryId AND committer_id IS NULL AND sha IN (:shas)
        """, nativeQuery = true)
    int bulkUpdateCommitterId(
            @Param("shas") List<String> shas,
            @Param("repositoryId") Long repositoryId,
            @Param("committerId") @Nullable Long committerId);

    /**
     * Upsert a commit by SHA and repository_id.
     * <p>
     * On conflict (same SHA in same repository), updates all mutable fields.
     * Uses {@code COALESCE} for nullable fields so null parameters preserve
     * existing database values (webhooks may provide less data than local git).
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO git_commit (sha, message, message_body, html_url, authored_at, committed_at,
            additions, deletions, changed_files, last_sync_at, created_at, updated_at,
            repository_id, author_id, committer_id, author_email, committer_email)
        VALUES (:sha, :message, :messageBody, :htmlUrl, :authoredAt, :committedAt,
            COALESCE(:additions, 0), COALESCE(:deletions, 0), COALESCE(:changedFiles, 0),
            :lastSyncAt, NOW(), NOW(),
            :repositoryId, :authorId, :committerId, :authorEmail, :committerEmail)
        ON CONFLICT (sha, repository_id) DO UPDATE SET
            message = EXCLUDED.message,
            message_body = COALESCE(EXCLUDED.message_body, git_commit.message_body),
            html_url = COALESCE(EXCLUDED.html_url, git_commit.html_url),
            authored_at = EXCLUDED.authored_at,
            committed_at = EXCLUDED.committed_at,
            additions = COALESCE(EXCLUDED.additions, git_commit.additions),
            deletions = COALESCE(EXCLUDED.deletions, git_commit.deletions),
            changed_files = COALESCE(EXCLUDED.changed_files, git_commit.changed_files),
            last_sync_at = EXCLUDED.last_sync_at,
            updated_at = NOW(),
            author_id = COALESCE(EXCLUDED.author_id, git_commit.author_id),
            committer_id = COALESCE(EXCLUDED.committer_id, git_commit.committer_id),
            author_email = COALESCE(EXCLUDED.author_email, git_commit.author_email),
            committer_email = COALESCE(EXCLUDED.committer_email, git_commit.committer_email)
        """, nativeQuery = true)
    void upsertCommit(
            @Param("sha") String sha,
            @Param("message") String message,
            @Param("messageBody") @Nullable String messageBody,
            @Param("htmlUrl") @Nullable String htmlUrl,
            @Param("authoredAt") @Nullable Instant authoredAt,
            @Param("committedAt") @Nullable Instant committedAt,
            @Param("additions") @Nullable Integer additions,
            @Param("deletions") @Nullable Integer deletions,
            @Param("changedFiles") @Nullable Integer changedFiles,
            @Param("lastSyncAt") Instant lastSyncAt,
            @Param("repositoryId") Long repositoryId,
            @Param("authorId") @Nullable Long authorId,
            @Param("committerId") @Nullable Long committerId,
            @Param("authorEmail") @Nullable String authorEmail,
            @Param("committerEmail") @Nullable String committerEmail);

    /**
     * Find SHAs of commits that have no contributor rows yet.
     * These commits need metadata enrichment (multi-author data + PR links).
     */
    @Query(value = """
        SELECT gc.sha FROM git_commit gc
        WHERE gc.repository_id = :repositoryId
          AND NOT EXISTS (
              SELECT 1 FROM commit_contributor cc WHERE cc.commit_id = gc.id
          )
        """, nativeQuery = true)
    List<String> findShasWithoutContributorsByRepositoryId(@Param("repositoryId") Long repositoryId);

    /** Links a commit to pull requests by PR number within the same repository. Idempotent ({@code ON CONFLICT DO NOTHING}). */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO commit_pull_request (commit_id, pull_request_id)
        SELECT :commitId, i.id
        FROM issue i
        WHERE i.repository_id = :repositoryId
          AND i.number IN (:prNumbers)
          AND i.issue_type = 'PULL_REQUEST'
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void linkCommitToPullRequests(
            @Param("commitId") Long commitId,
            @Param("repositoryId") Long repositoryId,
            @Param("prNumbers") List<Integer> prNumbers);

    /**
     * Link a pull request to commits by SHA within the same repository.
     * <p>
     * Used by the GitLab commit↔MR linker, which gets data MR-first from
     * GraphQL ({@code Project.mergeRequests { commitsWithoutMergeCommits { sha } }})
     * — the inverse of the commit-first GitHub path.
     * <p>
     * Idempotent: {@code ON CONFLICT DO NOTHING} skips pairs that are already
     * linked. Returns the number of rows inserted.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO commit_pull_request (commit_id, pull_request_id)
        SELECT gc.id, i.id
        FROM git_commit gc
        JOIN issue i ON i.repository_id = gc.repository_id
        WHERE gc.repository_id = :repositoryId
          AND gc.sha IN (:shas)
          AND i.number = :prNumber
          AND i.issue_type = 'PULL_REQUEST'
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int linkPullRequestToCommits(
            @Param("repositoryId") Long repositoryId,
            @Param("prNumber") Integer prNumber,
            @Param("shas") List<String> shas);

    /**
     * Bulk-update enrichment metadata fields on a commit.
     * <p>
     * Uses {@code COALESCE} so that NULL parameters preserve existing database values.
     * This ensures webhook-delivered data is not overwritten with NULLs if the
     * GraphQL response omits a field.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE git_commit SET
            additions = COALESCE(:additions, git_commit.additions),
            deletions = COALESCE(:deletions, git_commit.deletions),
            changed_files = COALESCE(:changedFiles, git_commit.changed_files),
            authored_at = COALESCE(:authoredAt, git_commit.authored_at),
            committed_at = COALESCE(:committedAt, git_commit.committed_at),
            message = COALESCE(:message, git_commit.message),
            message_body = COALESCE(:messageBody, git_commit.message_body),
            html_url = COALESCE(:htmlUrl, git_commit.html_url),
            signature_valid = COALESCE(:signatureValid, git_commit.signature_valid),
            authored_by_committer = COALESCE(:authoredByCommitter, git_commit.authored_by_committer),
            committed_via_web = COALESCE(:committedViaWeb, git_commit.committed_via_web),
            parent_count = COALESCE(:parentCount, git_commit.parent_count),
            signature_state = COALESCE(:signatureState, git_commit.signature_state),
            signature_was_signed_by_github = COALESCE(:signatureWasSignedByGitHub, git_commit.signature_was_signed_by_github),
            signature_signer_login = COALESCE(:signatureSignerLogin, git_commit.signature_signer_login),
            parent_shas = COALESCE(:parentShas, git_commit.parent_shas),
            status_check_rollup_state = COALESCE(:statusCheckRollupState, git_commit.status_check_rollup_state),
            on_behalf_of_login = COALESCE(:onBehalfOfLogin, git_commit.on_behalf_of_login),
            updated_at = NOW()
        WHERE git_commit.id = :commitId
        """, nativeQuery = true)
    void updateEnrichmentMetadata(
            @Param("commitId") Long commitId,
            @Param("additions") @Nullable Integer additions,
            @Param("deletions") @Nullable Integer deletions,
            @Param("changedFiles") @Nullable Integer changedFiles,
            @Param("authoredAt") @Nullable Instant authoredAt,
            @Param("committedAt") @Nullable Instant committedAt,
            @Param("message") @Nullable String message,
            @Param("messageBody") @Nullable String messageBody,
            @Param("htmlUrl") @Nullable String htmlUrl,
            @Param("signatureValid") @Nullable Boolean signatureValid,
            @Param("authoredByCommitter") @Nullable Boolean authoredByCommitter,
            @Param("committedViaWeb") @Nullable Boolean committedViaWeb,
            @Param("parentCount") @Nullable Integer parentCount,
            @Param("signatureState") @Nullable String signatureState,
            @Param("signatureWasSignedByGitHub") @Nullable Boolean signatureWasSignedByGitHub,
            @Param("signatureSignerLogin") @Nullable String signatureSignerLogin,
            @Param("parentShas") @Nullable String parentShas,
            @Param("statusCheckRollupState") @Nullable String statusCheckRollupState,
            @Param("onBehalfOfLogin") @Nullable String onBehalfOfLogin);

    /**
     * Populate parent metadata ({@code parent_count}, {@code parent_shas}) for a
     * single commit.
     * <p>
     * Uses {@code COALESCE} for {@code parent_shas} so a null parameter preserves
     * an existing value. {@code parent_count} is always written when non-null,
     * allowing the enrichment to recompute when the GitLab API reports a different
     * parent list (rare, but possible with force pushes).
     * <p>
     * This complements {@link #upsertCommit}, which does not accept parent fields
     * — the GitLab REST commit list endpoint exposes {@code parent_ids}, so we
     * backfill immediately after the upsert to keep the commit path atomic-enough
     * from a single caller's point of view while keeping {@code upsertCommit}'s
     * parameter surface small.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE git_commit SET
            parent_count = COALESCE(:parentCount, git_commit.parent_count),
            parent_shas = COALESCE(:parentShas, git_commit.parent_shas),
            updated_at = NOW()
        WHERE repository_id = :repositoryId AND sha = :sha
        """, nativeQuery = true)
    int updateParentMetadataBySha(
            @Param("repositoryId") Long repositoryId,
            @Param("sha") String sha,
            @Param("parentCount") @Nullable Integer parentCount,
            @Param("parentShas") @Nullable String parentShas);

    /** N most recent commits by an author as of {@code asOf}. Used by the AtomicChanges achievement evaluator. */
    @Query("""
        SELECT c FROM Commit c
        WHERE c.author.id = :authorId
        AND c.authoredAt <= :asOf
        ORDER BY c.authoredAt DESC
        """)
    List<Commit> findTopNByAuthorIdOrderByAuthoredAtDesc(
            @Param("authorId") @Nullable Long authorId, @Param("asOf") Instant asOf, Pageable pageable);

    /**
     * A pull request's commits through {@code commit_pull_request}, oldest authored first with the SHA as the
     * tiebreak so two commits authored in the same second stage in the same order on every review. Used by the
     * practice-review commit source.
     */
    @Query("""
        SELECT c FROM Commit c
        JOIN c.associatedPullRequests p
        WHERE p.id = :pullRequestId
        ORDER BY c.authoredAt ASC, c.sha ASC
        """)
    List<Commit> findByAssociatedPullRequestId(@Param("pullRequestId") Long pullRequestId, Pageable pageable);

    /** Commit by id with file changes eagerly loaded. Used by the CrossBoundary achievement evaluator. */
    @Query("""
        SELECT c FROM Commit c
        LEFT JOIN FETCH c.fileChanges
        WHERE c.id = :id
        """)
    Optional<Commit> findByIdWithFileChanges(@Param("id") Long id);

    /** Distinct file extensions across an author's commits as of {@code asOf}. Used by the Polyglot achievement evaluator. */
    @Query(value = """
        SELECT DISTINCT LOWER(
            CASE
                WHEN cf.filename LIKE '%.%' THEN SUBSTRING(cf.filename FROM '\\.([^.]+)$')
                ELSE NULL
            END
        )
        FROM commit_file_change cf
        JOIN git_commit gc ON cf.commit_id = gc.id
        WHERE gc.author_id = :authorId
        AND gc.authored_at <= :asOf
        AND cf.filename LIKE '%.%'
        """, nativeQuery = true)
    List<String> findDistinctFileExtensionsByAuthorId(
            @Param("authorId") @Nullable Long authorId, @Param("asOf") Instant asOf);
}
