package de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IssueTypeRepository extends JpaRepository<IssueType, String> {
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
     * Provider-scoped case-insensitive name lookup used as a fallback when a GitLab
     * issue lives in a subgroup whose {@code organization} has not had its own
     * {@code issue_type} seed rows materialised yet.
     * <p>
     * The current {@link de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuetype.GitLabIssueTypeSyncService}
     * only seeds types under the workspace's root accountLogin, but in GitLab each
     * subgroup becomes its own {@code Organization} row. Without this fallback an
     * issue synced from a subgroup would always resolve {@code issue_type_id} to
     * {@code NULL}. Issue type primary keys are GitLab-global GraphQL IDs
     * ({@code gid://gitlab/WorkItems::Type/1}), so a name match across the same
     * provider yields the exact same {@code IssueType} row regardless of which
     * organization owns it.
     * <p>
     * {@link Pageable} with size 1 gives us "first" semantics without provoking
     * Spring Data runtime warnings about unordered single-row queries.
     */
    @Query("""
        SELECT it
        FROM IssueType it
        WHERE lower(it.name) = lower(:name)
        AND it.isEnabled = true
        AND it.organization.provider.id = :providerId
        ORDER BY it.organization.id ASC
        """)
    List<IssueType> findByProviderIdAndNameIgnoreCase(
            @Param("providerId") Long providerId, @Param("name") String name, Pageable pageable);

    default Optional<IssueType> findFirstByProviderIdAndNameIgnoreCase(Long providerId, String name) {
        List<IssueType> results = findByProviderIdAndNameIgnoreCase(providerId, name, Pageable.ofSize(1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Resolves an issue type by name for the same provider as the given organization.
     * <p>
     * Uses a JPQL subquery to find the organization's provider id so callers never
     * have to dereference {@code organization.getProvider()} on a detached lazy proxy —
     * which was raising {@code LazyInitializationException} during GitLab issue sync
     * when the {@code Repository}'s Organization proxy outlived its Hibernate session.
     */
    @Query("""
        SELECT it
        FROM IssueType it
        WHERE lower(it.name) = lower(:name)
        AND it.isEnabled = true
        AND it.organization.provider.id = (
            SELECT o.provider.id FROM Organization o WHERE o.id = :organizationId
        )
        ORDER BY it.organization.id ASC
        """)
    List<IssueType> findByOrganizationProviderAndNameIgnoreCase(
            @Param("organizationId") Long organizationId, @Param("name") String name, Pageable pageable);

    default Optional<IssueType> findFirstByOrganizationProviderAndNameIgnoreCase(Long organizationId, String name) {
        List<IssueType> results = findByOrganizationProviderAndNameIgnoreCase(organizationId, name, Pageable.ofSize(1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
