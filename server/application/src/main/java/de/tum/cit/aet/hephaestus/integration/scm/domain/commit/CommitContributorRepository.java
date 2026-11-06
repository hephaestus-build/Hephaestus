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
     * {@code userId} is a local user id, never a provider's id: for that, use
     * {@link #upsertContributorByNativeUserId}. It is validated against the "user" table via a
     * subquery to avoid FK violations.
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
     * Upsert a commit contributor that a provider names by its own user id. The id is resolved to a local user
     * on the provider of the commit's repository, in this statement: a provider's ids and local ids share a range,
     * so the provider id must never be stored as a local id. A user who is not synced yet stores {@code NULL}.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO commit_contributor (commit_id, user_id, role, name, email, ordinal)
        VALUES (
            :commitId,
            (SELECT u.id FROM "user" u
             JOIN repository r ON r.provider_id = u.provider_id
             JOIN git_commit c ON c.repository_id = r.id
             WHERE c.id = :commitId AND u.native_id = :nativeUserId),
            :role, :name, :email, :ordinal)
        ON CONFLICT ON CONSTRAINT uq_commit_contributor_commit_email_role DO UPDATE SET
            name = COALESCE(EXCLUDED.name, commit_contributor.name),
            ordinal = EXCLUDED.ordinal,
            user_id = COALESCE(EXCLUDED.user_id, commit_contributor.user_id)
        """, nativeQuery = true)
    void upsertContributorByNativeUserId(
            @Param("commitId") Long commitId,
            @Param("nativeUserId") @Nullable Long nativeUserId,
            @Param("role") String role,
            @Param("name") @Nullable String name,
            @Param("email") String email,
            @Param("ordinal") int ordinal);

    /**
     * Delete all contributors for a commit.
     */
    void deleteByCommitId(Long commitId);

    /**
     * Backfill {@code user_id} on every contributor row whose email matches
     * {@code email} (case-insensitive) and that currently has no user attached.
     *
     * <p>Used by the GitLab commit→MR linker after it harvests
     * {@code (authorEmail, author.username)} pairs from the MR GraphQL sweep:
     * the raw commit fingerprint captured by the git backfill only records an
     * email, but GitLab's server-side resolver knows the author account for
     * every email it recognises. Propagating that mapping back to the ledger
     * closes the gap for authors whose email does not match the
     * {@link de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver}
     * TUM/LRZ/noreply heuristics (e.g. {@code firstname.lastname@tum.de}).
     *
     * <p>The scope is intentionally narrow: we never overwrite an existing
     * {@code user_id}, and repositories other than the one being synced are
     * updated intentionally because contributor rows in separate repos share
     * the same email/user identity.
     *
     * @param email  the commit-author email to match (case-insensitive)
     * @param userId the database user id to attach
     * @return the number of rows updated
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE commit_contributor
        SET user_id = :userId
        WHERE LOWER(email) = LOWER(:email) AND user_id IS NULL
        """, nativeQuery = true)
    int backfillUserIdByEmail(@Param("email") String email, @Param("userId") @Nullable Long userId);
}
