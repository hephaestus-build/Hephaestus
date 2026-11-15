package de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IssueTypeRepository extends JpaRepository<IssueType, Long> {
    /** The organization's row for one provider type id; the same id on another instance is another row. */
    Optional<IssueType> findByOrganizationIdAndNativeId(Long organizationId, String nativeId);

    /**
     * Find all issue types for an organization (including disabled).
     * Used for cleanup of deleted issue types during sync.
     */
    @Query("""
        SELECT it
        FROM IssueType it
        WHERE it.organization.id = :organizationId
        ORDER BY it.name ASC
        """)
    List<IssueType> findAllByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * Find an enabled issue type by organization and name (case-insensitive).
     * <p>
     * Used by GitLab issue sync to resolve {@code issue.issue_type_id} from the
     * GraphQL {@code Issue.type} enum ({@code ISSUE}, {@code TASK}, {@code INCIDENT}, …).
     */
    @Query("""
        SELECT it
        FROM IssueType it
        WHERE it.organization.id = :organizationId
        AND lower(it.name) = lower(:name)
        AND it.isEnabled = true
        """)
    Optional<IssueType> findByOrganizationIdAndNameIgnoreCase(
            @Param("organizationId") Long organizationId, @Param("name") String name);

    /**
     * The enabled type of this name in the nearest parent group of the organization, on the same provider instance.
     * GitLab makes each subgroup its own organization without issue types of its own: the sync records them for the
     * workspace's group. A sibling group, or a group of the same path on another instance, never answers.
     * <p>
     * The organization is read in the query, so a caller never dereferences a detached lazy organization proxy.
     * {@link Pageable} with size 1 gives "first" semantics.
     */
    @Query("""
        SELECT it
        FROM IssueType it, Organization o
        WHERE o.id = :organizationId
        AND lower(it.name) = lower(:name)
        AND it.isEnabled = true
        AND it.organization.provider.id = o.provider.id
        AND SUBSTRING(o.login, 1, LENGTH(it.organization.login) + 1) = CONCAT(it.organization.login, '/')
        ORDER BY LENGTH(it.organization.login) DESC
        """)
    List<IssueType> findInParentGroupsByName(
            @Param("organizationId") Long organizationId, @Param("name") String name, Pageable pageable);

    default Optional<IssueType> findInNearestParentGroupByName(Long organizationId, String name) {
        return findInParentGroupsByName(organizationId, name, Pageable.ofSize(1)).stream()
                .findFirst();
    }
}
