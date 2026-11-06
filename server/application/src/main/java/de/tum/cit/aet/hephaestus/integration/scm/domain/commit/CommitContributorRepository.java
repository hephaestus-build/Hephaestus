package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for CommitContributor entities.
 */
@Repository
@WorkspaceAgnostic("Contributors scoped through commit_id -> repository.workspace_id")
public interface CommitContributorRepository extends JpaRepository<CommitContributor, Long> {
    /**
     * Find all contributors for a commit, ordered by role and ordinal.
     */
    @Query("""
        SELECT cc FROM CommitContributor cc
        LEFT JOIN FETCH cc.user
        WHERE cc.commit.id = :commitId
        ORDER BY cc.role, cc.ordinal
        """)
    List<CommitContributor> findByCommitIdWithUser(@Param("commitId") Long commitId);

    /**
     * Upsert a commit contributor. On conflict (same commit, email, role),
     * updates the name, ordinal, and user_id if a better resolution is available.
     * <p>
     * The user_id is validated against the "user" table via a subquery to avoid FK violations
     * when the GitHub user hasn't been synced into our database yet.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO commit_contributor (commit_id, user_id, role, name, email, ordinal)
        VALUES (:commitId, (SELECT id FROM "user" WHERE id = :userId), :role, :name, :email, :ordinal)
        ON CONFLICT ON CONSTRAINT uq_commit_contributor_commit_email_role DO UPDATE SET
            name = COALESCE(EXCLUDED.name, commit_contributor.name),
            ordinal = EXCLUDED.ordinal,
            user_id = COALESCE(EXCLUDED.user_id, commit_contributor.user_id)
        """, nativeQuery = true)
    void upsertContributor(
            @Param("commitId") Long commitId,
            @Param("userId") @Nullable Long userId,
            @Param("role") String role,
            @Param("name") @Nullable String name,
            @Param("email") String email,
            @Param("ordinal") int ordinal);

    /**
     * Delete all contributors for a commit.
     */
    void deleteByCommitId(Long commitId);

    /**
     * Backfill {@code user_id} on the contributor rows of one repository whose email matches
     * {@code email} (case-insensitive) and that currently have no user attached.
     *
     * <p>Used by the GitLab commit→MR linker after it harvests
     * {@code (authorEmail, author.username)} pairs from the MR GraphQL sweep:
     * the raw commit fingerprint captured by the git backfill only records an
     * email, but GitLab's server-side resolver knows the author account for
     * every email it recognises. This propagates that mapping back to the ledger.
     *
     * <p>The write stays inside the repository being synced and never overwrites an existing
     * {@code user_id}. A commit's email is unverified, and {@code user} rows are per provider,
     * so the same email in another repository may belong to someone else.
     *
     * @param email        the commit-author email to match (case-insensitive)
     * @param repositoryId the repository being synced
     * @param userId       the database user id to attach
     * @return the number of rows updated
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE commit_contributor
        SET user_id = :userId
        WHERE LOWER(email) = LOWER(:email) AND user_id IS NULL
          AND commit_id IN (SELECT id FROM git_commit WHERE repository_id = :repositoryId)
        """, nativeQuery = true)
    int backfillUserIdByEmail(
            @Param("email") String email,
            @Param("repositoryId") Long repositoryId,
            @Param("userId") @Nullable Long userId);
}
