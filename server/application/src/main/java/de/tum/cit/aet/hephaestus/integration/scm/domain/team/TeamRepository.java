package de.tum.cit.aet.hephaestus.integration.scm.domain.team;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Team has no workspace_id; the statement inspector cannot scope it. Workspace read/authorization "
        + "paths MUST scope by (organization, provider_id) via the *AndProviderId finders below — the bare "
        + "organization-string finders match same-named orgs across providers and leak across tenants.")
public interface TeamRepository extends JpaRepository<Team, Long> {
    Optional<Team> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    List<Team> findAllByNativeIdInAndProviderId(Collection<Long> nativeIds, Long providerId);

    /**
     * Provider-scoped name lookup — the workspace-safe form. A same-named team on a different
     * provider (whose {@code organization} string collides) cannot leak into path resolution.
     */
    List<Team> findAllByNameAndProviderId(String name, Long providerId);

    /**
     * Provider-agnostic organization enumeration. Sync-engine use only ({@code GitHubTeamSyncService} /
     * {@code GitLabTeamSyncService} stale-team cleanup, where the provider is already fixed in-loop).
     * Workspace read/auth paths MUST use {@link #findAllByOrganizationIgnoreCaseAndProviderId}.
     */
    List<Team> findAllByOrganizationIgnoreCase(String organization);

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
     * Fetch teams with collections eagerly loaded for DTO conversion.
     * Uses EntityGraph to fetch repoPermissions (with nested repository),
     * and memberships (with users).
     *
     * <p>Note: Repository labels are NOT included in EntityGraph to avoid
     * Cartesian product explosion. They are batch-fetched via @BatchSize(50)
     * on Repository.labels when accessed.
     *
     * <p>Note: Team labels are managed via scope-specific settings
     * (via consuming module) and are fetched separately.
     */
    @EntityGraph(attributePaths = {"repoPermissions", "repoPermissions.repository", "memberships", "memberships.user"})
    List<Team> findWithCollectionsByOrganizationIgnoreCaseAndProviderId(String organization, Long providerId);

    /**
     * Fetch a single team by ID with collections eagerly loaded for DTO conversion.
     *
     * <p>Note: Repository labels are NOT included in EntityGraph to avoid
     * Cartesian product explosion. They are batch-fetched via @BatchSize(50)
     * on Repository.labels when accessed.
     *
     * <p>Note: Team labels are managed via scope-specific settings
     * (via consuming module) and are fetched separately.
     */
    @EntityGraph(attributePaths = {"repoPermissions", "repoPermissions.repository", "memberships", "memberships.user"})
    Optional<Team> findWithCollectionsById(Long id);
}
