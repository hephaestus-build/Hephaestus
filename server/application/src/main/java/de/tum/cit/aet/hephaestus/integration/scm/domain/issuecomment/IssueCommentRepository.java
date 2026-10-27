package de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NoteIdProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.RepositoryItemCountProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Comments scoped through issue_id -> repository.workspace_id")
public interface IssueCommentRepository extends JpaRepository<IssueComment, Long> {
    /**
     * Comment {@code c} of repository {@code :repositoryId} hangs on issue row {@code i}, and that number is a
     * stored GitHub pull request {@code p} of the same provider. GitHub numbers issues and pull requests in
     * one space per repository, so {@code p} is the comment's parent. Native ids are not compared: an issue
     * resource's id is not the pull request's. GitLab numbers issues and merge requests separately and is
     * never a witness.
     */
    String CANONICAL_PULL_REQUEST_PARENT = """
            FROM issue i JOIN issue p ON p.repository_id=i.repository_id AND p.number=i.number
                AND p.provider_id=i.provider_id AND p.issue_type='PULL_REQUEST' AND p.id<>i.id
            JOIN identity_provider ip ON ip.id=p.provider_id AND ip.type='GITHUB'
            JOIN repository r ON r.id=i.repository_id AND r.provider_id=i.provider_id
            WHERE i.id=c.issue_id AND i.issue_type='ISSUE' AND i.repository_id=:repositoryId
              AND c.provider_id=i.provider_id
            """;

    /** The highest comment id the repair may move now; later comments wait for the next pass. */
    @Query(
            value = "SELECT coalesce(max(c.id),0) FROM issue_comment c WHERE EXISTS (SELECT 1 "
                    + CANONICAL_PULL_REQUEST_PARENT + ")",
            nativeQuery = true)
    long findCanonicalParentCeiling(@Param("repositoryId") long repositoryId);

    @Query(
            value = "SELECT c.id FROM issue_comment c WHERE c.id > :after AND c.id <= :ceiling AND EXISTS (SELECT 1 "
                    + CANONICAL_PULL_REQUEST_PARENT + ") ORDER BY c.id LIMIT :limit",
            nativeQuery = true)
    List<Long> findCanonicalParentChunk(
            @Param("repositoryId") long repositoryId,
            @Param("after") long after,
            @Param("ceiling") long ceiling,
            @Param("limit") int limit);

    /**
     * Moves only the selected comments from a contradicted issue row to their pull request.
     * The witness is evaluated again on the row being updated, so a current parent that no longer matches the
     * witness is left as it is. Only the parent column changes: no other comment field, source row or collection.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE issue_comment c SET issue_id=p.id " + CANONICAL_PULL_REQUEST_PARENT + " AND c.id IN :ids",
            nativeQuery = true)
    int moveToCanonicalPullRequest(@Param("repositoryId") long repositoryId, @Param("ids") Collection<Long> ids);

    @Query("SELECT c.id AS id, c.nativeId AS nativeId FROM IssueComment c WHERE c.issue.id = :parentId")
    List<NoteIdProjection> findNoteIdsByParentId(@Param("parentId") long parentId);

    @Modifying
    @Query("DELETE FROM IssueComment c WHERE c.issue.id = :parentId AND c.id IN :ids")
    int deleteReconciled(@Param("parentId") long parentId, @Param("ids") Collection<Long> ids);

    Optional<IssueComment> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    /** Locks the comment, so a compare with its stored {@code updated_at} and the write after it are atomic. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM IssueComment c WHERE c.nativeId = :nativeId AND c.provider.id = :providerId")
    Optional<IssueComment> findForUpdateByNativeIdAndProviderId(
            @Param("nativeId") Long nativeId, @Param("providerId") Long providerId);

    /**
     * Per-repository comment count for the sync-observability breakdown, batched over every repository
     * of a connection in one grouped join. Counts comments on pull requests as well as on pure issues —
     * both are {@code Issue} rows under single-table inheritance, and the sync path that fetches them is
     * the same one, so splitting them here would imply a distinction the sync doesn't make.
     *
     * <p>Comments of a tombstoned parent ({@code c.issue.deletedAt IS NOT NULL}) are excluded, matching
     * how the issue and pull-request counts already drop tombstoned rows. A comment has no tombstone of
     * its own: it goes away with the issue it hangs off, so the parent's tombstone is the only signal
     * there is. Counting them would reintroduce on the child row exactly the permanent inflation the
     * deletion sweep removes from the parent — the admin would see an issue count fall while its
     * comment count stayed put.
     *
     * <p>The predicate rides the {@code c.issue} join that the grouping already needs, so this stays one
     * grouped query for the whole connection.
     */
    @Query("SELECT c.issue.repository.id AS repositoryId, COUNT(c) AS itemCount FROM IssueComment c "
            + "WHERE c.issue.repository.id IN :repositoryIds AND c.issue.deletedAt IS NULL "
            + "GROUP BY c.issue.repository.id")
    List<RepositoryItemCountProjection> countGroupedByRepositoryIds(
            @Param("repositoryIds") Collection<Long> repositoryIds);

    /** Comments by id, each with its issue or pull request, that work's author and its repository. */
    @Query("""
        SELECT ic
        FROM IssueComment ic
        LEFT JOIN FETCH ic.issue i
        LEFT JOIN FETCH i.author
        LEFT JOIN FETCH i.repository
        WHERE ic.id IN :ids
        """)
    List<IssueComment> findAllByIdWithRelations(@Param("ids") Collection<Long> ids);

    @Query("SELECT ic FROM IssueComment ic LEFT JOIN FETCH ic.author "
            + "WHERE ic.issue.id = :issueId ORDER BY ic.createdAt DESC, ic.id DESC")
    List<IssueComment> findRecentByIssueIdWithAuthor(@Param("issueId") Long issueId, Pageable pageable);

    @Query("SELECT ic FROM IssueComment ic LEFT JOIN FETCH ic.author "
            + "WHERE ic.issue.id = :issueId AND ic.body IS NOT NULL AND TRIM(ic.body) <> '' "
            + "AND ic.body NOT LIKE CONCAT('%', :excludedMarker, '%') "
            + "ORDER BY ic.createdAt DESC, ic.id DESC")
    List<IssueComment> findRecentHumanByIssueIdWithAuthor(
            @Param("issueId") Long issueId, @Param("excludedMarker") String excludedMarker, Pageable pageable);

    /**
     * The non-empty comments, oldest first, as stored. Delivery provenance is applied by the caller. A projection rather than
     * entities: in the transaction that wrote a comment, an entity is the managed instance with the values Java
     * set, while the column holds them as PostgreSQL rounded them, so a digest of entities would differ from the
     * same digest taken after commit.
     */
    @Query("SELECT ic.id AS id, ic.nativeId AS nativeId, a.login AS authorLogin, a.nativeId AS authorNativeId, "
            + "a.type AS authorType, ic.createdAt AS createdAt, ic.updatedAt AS updatedAt, ic.body AS body "
            + "FROM IssueComment ic LEFT JOIN ic.author a "
            + "WHERE ic.issue.id = :issueId AND ic.body IS NOT NULL AND TRIM(ic.body) <> '' "
            + "ORDER BY ic.createdAt ASC NULLS LAST, ic.id ASC")
    List<StoredComment> findStoredByIssueId(@Param("issueId") long issueId);

    @Query("SELECT COUNT(ic) FROM IssueComment ic WHERE ic.issue.id = :issueId")
    long countByIssueId(@Param("issueId") long issueId);

    interface StoredComment {
        Long getId();

        Long getNativeId();

        @Nullable
        String getAuthorLogin();

        /** Native attribution; the issue revision digest does not include account metadata. */
        @Nullable
        Long getAuthorNativeId();

        User.@Nullable Type getAuthorType();

        @Nullable
        Instant getCreatedAt();

        @Nullable
        Instant getUpdatedAt();

        String getBody();
    }
}
