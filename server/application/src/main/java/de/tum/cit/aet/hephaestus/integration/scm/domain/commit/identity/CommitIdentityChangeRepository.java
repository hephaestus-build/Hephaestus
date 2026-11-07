package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The record of changed commit links. Each write states its old and new user, its reason and its time. */
@Repository
public interface CommitIdentityChangeRepository extends JpaRepository<CommitIdentityChange, Long> {
    /** {@code newUserId} 0 clears the link, because the provider says that no account owns the email. */
    @WorkspaceAgnostic("Instance-wide correction of shared commit rows, bound to one repository")
    @Modifying
    @Transactional
    @Query(value = """
        WITH target AS (
          SELECT id, author_id FROM git_commit
          WHERE repository_id = :repositoryId
            AND author_email = :email
            AND author_id IS DISTINCT FROM NULLIF(CAST(:newUserId AS bigint), 0)
          FOR UPDATE),
        moved AS (
          UPDATE git_commit c SET author_id = NULLIF(CAST(:newUserId AS bigint), 0)
          FROM target t WHERE c.id = t.id
          RETURNING c.id, t.author_id AS old_user_id)
        INSERT INTO commit_identity_change (target, row_id, old_user_id, new_user_id, outcome, changed_at)
        SELECT 'git_commit.author_id', id, old_user_id, NULLIF(CAST(:newUserId AS bigint), 0), :outcome, :changedAt
        FROM moved
        """, nativeQuery = true)
    int changeAuthors(
            @Param("repositoryId") long repositoryId,
            @Param("email") String email,
            @Param("newUserId") long newUserId,
            @Param("outcome") String outcome,
            @Param("changedAt") Instant changedAt);

    /** {@code newUserId} 0 clears the link, because the provider says that no account owns the email. */
    @WorkspaceAgnostic("Instance-wide correction of shared commit rows, bound to one repository")
    @Modifying
    @Transactional
    @Query(value = """
        WITH target AS (
          SELECT id, committer_id FROM git_commit
          WHERE repository_id = :repositoryId
            AND committer_email = :email
            AND committer_id IS DISTINCT FROM NULLIF(CAST(:newUserId AS bigint), 0)
          FOR UPDATE),
        moved AS (
          UPDATE git_commit c SET committer_id = NULLIF(CAST(:newUserId AS bigint), 0)
          FROM target t WHERE c.id = t.id
          RETURNING c.id, t.committer_id AS old_user_id)
        INSERT INTO commit_identity_change (target, row_id, old_user_id, new_user_id, outcome, changed_at)
        SELECT 'git_commit.committer_id', id, old_user_id, NULLIF(CAST(:newUserId AS bigint), 0), :outcome, :changedAt
        FROM moved
        """, nativeQuery = true)
    int changeCommitters(
            @Param("repositoryId") long repositoryId,
            @Param("email") String email,
            @Param("newUserId") long newUserId,
            @Param("outcome") String outcome,
            @Param("changedAt") Instant changedAt);

    /** {@code newUserId} 0 clears the link, because the provider says that no account owns the email. */
    @WorkspaceAgnostic("Instance-wide correction of shared commit rows, bound to one repository")
    @Modifying
    @Transactional
    @Query(value = """
        WITH target AS (
          SELECT cc.id, cc.user_id FROM commit_contributor cc
          JOIN git_commit gc ON gc.id = cc.commit_id
          WHERE gc.repository_id = :repositoryId
            AND cc.email = :email
            AND cc.user_id IS DISTINCT FROM NULLIF(CAST(:newUserId AS bigint), 0)
          FOR UPDATE OF cc),
        moved AS (
          UPDATE commit_contributor c SET user_id = NULLIF(CAST(:newUserId AS bigint), 0)
          FROM target t WHERE c.id = t.id
          RETURNING c.id, t.user_id AS old_user_id)
        INSERT INTO commit_identity_change (target, row_id, old_user_id, new_user_id, outcome, changed_at)
        SELECT 'commit_contributor.user_id', id, old_user_id, NULLIF(CAST(:newUserId AS bigint), 0), :outcome, :changedAt
        FROM moved
        """, nativeQuery = true)
    int changeContributors(
            @Param("repositoryId") long repositoryId,
            @Param("email") String email,
            @Param("newUserId") long newUserId,
            @Param("outcome") String outcome,
            @Param("changedAt") Instant changedAt);

    @WorkspaceAgnostic("Instance-wide retention of the correction record")
    @Modifying
    @Transactional
    @Query("DELETE FROM CommitIdentityChange c WHERE c.changedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
