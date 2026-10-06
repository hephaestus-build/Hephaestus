package de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NoteIdProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.RepositoryItemCountProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
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

@Repository
@WorkspaceAgnostic("Comments scoped through issue_id -> repository.workspace_id")
public interface IssueCommentRepository extends JpaRepository<IssueComment, Long> {
    @Query("SELECT c.id AS id, c.nativeId AS nativeId FROM IssueComment c WHERE c.issue.id = :parentId")
    List<NoteIdProjection> findNoteIdsByParentId(@Param("parentId") long parentId);

    @Modifying
    @Query("DELETE FROM IssueComment c WHERE c.issue.id = :parentId AND c.id IN :ids")
    int deleteReconciled(@Param("parentId") long parentId, @Param("ids") Collection<Long> ids);

    Optional<IssueComment> findByNativeIdAndProviderId(Long nativeId, Long providerId);

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
