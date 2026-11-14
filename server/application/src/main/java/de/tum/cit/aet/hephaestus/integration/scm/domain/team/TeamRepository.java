package de.tum.cit.aet.hephaestus.integration.scm.domain.team;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Teams are shared mirror rows with no {@code workspace_id} (ADR 0077). Every finder is keyed by an id or by
 * {@code provider_id}: an organization string alone matches same-named organizations on other providers.
 */
@Repository
@WorkspaceAgnostic("SCM mirror rows are shared across workspaces; callers validate the provider or monitored scope")
public interface TeamRepository extends JpaRepository<Team, Long> {
    Optional<Team> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    List<Team> findAllByNativeIdInAndProviderId(Collection<Long> nativeIds, Long providerId);

    /** Provider-scoped organization enumeration — matches the {@code (provider_id, organization)} key prefix. */
    List<Team> findAllByOrganizationIgnoreCaseAndProviderId(String organization, Long providerId);

    /**
     * Find a team by its natural key, provider-scoped. Without the provider a same-named team in a
     * same-named org on another provider matches, and the sync then overwrites that tenant's row.
     *
     * @param organization the organization login (case-insensitive)
     * @param name the team name
     * @param providerId the git provider ID
     * @return the team if found
     */
    Optional<Team> findByOrganizationIgnoreCaseAndNameAndProviderId(String organization, String name, Long providerId);

    /**
     * Find a team by organization, slug, and provider — the full natural key.
     * <p>
     * For GitHub: slug = team slug. For GitLab: slug = relative path from root group.
     *
     * @param organization the organization login (case-insensitive)
     * @param slug the team slug
     * @param providerId the git provider ID
     * @return the team if found
     */
    Optional<Team> findByOrganizationIgnoreCaseAndSlugAndProviderId(String organization, String slug, Long providerId);

    /**
     * Every team mirrored for one organization on one provider — the org-tier erase set used by
     * {@code workspace.ScmWorkspaceContentEraser} once no non-purged workspace is bound to that
     * organization any more. Provider-scoped so a same-named org on a second GitLab instance is not
     * swept up. Returns entities (not a bulk delete) so {@code Team}'s {@code CascadeType.REMOVE}
     * reaches {@code team_membership}.
     *
     * @param organization the organization login / root group path (case-insensitive)
     * @param providerId   the identity provider instance
     */
    List<Team> findByOrganizationIgnoreCaseAndProviderId(String organization, Long providerId);

    /**
     * Teams with their repository permissions. A team's memberships load in a second query
     * ({@link #findWithMembershipsByOrganizationIgnoreCaseAndProviderId}) in the same transaction: one fetch of both
     * collections returns a row for every pair of permission and membership.
     */
    @EntityGraph(attributePaths = {"repoPermissions", "repoPermissions.repository"})
    List<Team> findWithRepoPermissionsByOrganizationIgnoreCaseAndProviderId(String organization, Long providerId);

    /** Teams with their memberships, for the teams that the permission query already loaded. */
    @EntityGraph(attributePaths = {"memberships", "memberships.user"})
    List<Team> findWithMembershipsByOrganizationIgnoreCaseAndProviderId(String organization, Long providerId);

    @EntityGraph(attributePaths = {"repoPermissions", "repoPermissions.repository"})
    Optional<Team> findWithRepoPermissionsById(Long id);

    @EntityGraph(attributePaths = {"memberships", "memberships.user"})
    Optional<Team> findWithMembershipsById(Long id);
}
