package de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.RepositoryItemCountProjection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Comments scoped through issue_id -> repository.workspace_id")
public interface IssueCommentRepository extends JpaRepository<IssueComment, Long> {
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
}
