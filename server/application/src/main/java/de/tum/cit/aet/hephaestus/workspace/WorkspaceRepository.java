package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Workspace is the tenant root - queries manage workspaces themselves")
public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {
    Optional<Workspace> findFirstByOrderByIdAsc();

    /**
     * Pessimistic lock, for a read whose value is about to be snapshotted and mutated (the audited
     * AI-settings writes). Without it the before-snapshot and the write are not serialized: two
     * concurrent admin PATCHes both read the same prior state, Hibernate's full-column UPDATE makes the
     * later one silently revert the earlier's field, and the audit trail ends up asserting a transition
     * that never survived — with no row for the write that undid it.
     */
    @WorkspaceAgnostic("Locking read of the tenant root itself, by its own id")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT w FROM Workspace w WHERE w.id = :id")
    Optional<Workspace> findByIdForUpdate(@Param("id") Long id);

    /** Account deletion already owns the account lock, so it must not wait behind a workspace-first mutation. */
    @WorkspaceAgnostic("Non-waiting lock of the tenant root for account deletion's last-owner check")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT w FROM Workspace w WHERE w.id = :id")
    Optional<Workspace> findByIdForUpdateNowait(@Param("id") Long id);

    /**
     * {@link #findByIdForUpdate} for a caller that only has the slug. One locking query, not a lookup
     * followed by a locking read: the second call returns the persistence context's existing instance
     * without refreshing it, leaving the caller holding exactly the pre-lock state.
     */
    @WorkspaceAgnostic("Locking read of the tenant root itself, by its own slug")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT w FROM Workspace w WHERE w.workspaceSlug = :slug")
    Optional<Workspace> findByWorkspaceSlugForUpdate(@Param("slug") String slug);

    /**
     * Reverse-lookup a workspace by its GitHub App installation id. Joins through the
     * {@code Connection} row whose {@code kind='GITHUB'} and {@code instance_key=installationId}.
     * Cross-workspace collision is structurally impossible at this layer because the
     * inline-create-from-installation path in {@code GithubLifecycleListener} only writes a
     * {@code Connection} row for the workspace it is creating, so the join is at-most-one.
     */
    default Optional<Workspace> findByInstallationId(Long installationId) {
        if (installationId == null) {
            return Optional.empty();
        }
        return findByGitHubInstallationInstanceKey(installationId.toString());
    }

    default Optional<Workspace> findByInstallationIdForUpdate(Long installationId) {
        if (installationId == null) {
            return Optional.empty();
        }
        return findWorkspaceIdByGitHubInstallationInstanceKey(installationId.toString())
                .flatMap(this::findByIdForUpdate);
    }

    default Optional<Workspace> findActiveByInstallationIdForUpdate(Long installationId) {
        return findByInstallationIdForUpdate(installationId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE);
    }

    @Query("""
        SELECT c.workspace
        FROM Connection c
        WHERE c.kind = de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind.GITHUB
          AND c.instanceKey = :instanceKey
        """)
    Optional<Workspace> findByGitHubInstallationInstanceKey(@Param("instanceKey") String instanceKey);

    @Query("""
        SELECT c.workspace.id
        FROM Connection c
        WHERE c.kind = de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind.GITHUB
          AND c.instanceKey = :instanceKey
        """)
    Optional<Long> findWorkspaceIdByGitHubInstallationInstanceKey(@Param("instanceKey") String instanceKey);

    Optional<Workspace> findByRepositoriesToMonitor_NameWithOwner(String nameWithOwner);

    Optional<Workspace> findByOrganization_Login(String login);

    /**
     * The provider id of the workspace's synced {@code Organization} — the {@code provider_id} its
     * teams are stamped with at sync time (see {@link WorkspaceTeamScopeResolver}). Empty when there
     * is no synced organization. A scalar projection, so it works on a detached workspace without
     * initializing the lazy {@code organization} association.
     */
    @Query("SELECT w.organization.provider.id FROM Workspace w WHERE w.id = :workspaceId")
    Optional<Long> findOrganizationProviderIdByWorkspaceId(@Param("workspaceId") Long workspaceId);

    Optional<Workspace> findByAccountLoginIgnoreCase(String login);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT w FROM Workspace w WHERE LOWER(w.accountLogin) = LOWER(:login)")
    Optional<Workspace> findByAccountLoginIgnoreCaseForUpdate(@Param("login") String login);

    List<Workspace> findAllByAccountLoginIgnoreCase(String login);

    Optional<Workspace> findByWorkspaceSlug(String workspaceSlug);

    boolean existsByWorkspaceSlug(String workspaceSlug);

    boolean existsByOrganizationId(Long organizationId);

    boolean existsByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * Org-tier orphan check for {@link ScmWorkspaceContentEraser}: how many workspaces OTHER than
     * {@code excludedWorkspaceId} are still bound to this organization and not already purged.
     * A non-zero count would mean another tenant still holds a lawful basis for the org-tier mirror
     * ({@code team}, {@code team_membership}, {@code organization_membership}), so it must survive
     * the erasing workspace.
     *
     * <p><b>Defensive only.</b> Unlike {@code repository}, which many workspaces genuinely share, the
     * organization binding is exclusive: {@code Workspace.organization} is a {@code @OneToOne} over a
     * {@code unique = true} {@code organization_id} column, so an organization backs at most one
     * workspace and this count is always 0 in production. It is kept so
     * {@link ScmWorkspaceContentEraser} stays correct if that 1:1 mapping is ever relaxed.
     */
    @Query("""
        SELECT COUNT(w) FROM Workspace w
        WHERE w.organization.id = :organizationId
          AND w.id <> :excludedWorkspaceId
          AND w.status <> de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus.PURGED
        """)
    long countOtherActiveWorkspacesForOrganization(
            @Param("organizationId") Long organizationId, @Param("excludedWorkspaceId") Long excludedWorkspaceId);

    List<Workspace> findByStatusNot(Workspace.WorkspaceStatus status);

    List<Workspace> findByStatusNotAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus status);

    List<Workspace> findByStatus(Workspace.WorkspaceStatus status);

    List<Workspace> findByStatusAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus status);

    /**
     * Resolves the workspace id from a repository id via the {@code RepositoryToMonitor} join.
     * Used by mentor-cache invalidation: domain events carry {@code repositoryId} but mentor
     * caches key on {@code workspaceId}.
     *
     * <p>The join is on {@code nameWithOwner} (no FK from {@code RepositoryToMonitor} to {@code
     * Repository}), and {@code repository_to_monitor.name_with_owner} has no unique constraint, so two
     * monitors that share a {@code nameWithOwner} would yield more than one row. Rather than assert a
     * cardinality the schema does not enforce — which would surface as an uncaught
     * {@code IncorrectResultSizeDataAccessException} inside the async invalidation listener — this picks
     * the lowest workspace id deterministically and caps the result to one row.
     *
     * @return the resolved workspace id, or {@code Optional.empty()} if the repository is not monitored
     *         anywhere
     */
    default Optional<Long> findWorkspaceIdByRepositoryId(Long repositoryId) {
        List<Long> ids = findWorkspaceIdsByRepositoryId(repositoryId, PageRequest.of(0, 1));
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    @Query("""
        SELECT m.workspace.id
        FROM RepositoryToMonitor m
        JOIN Repository r ON r.nameWithOwner = m.nameWithOwner
        WHERE r.id = :repositoryId
        ORDER BY m.workspace.id
        """)
    List<Long> findWorkspaceIdsByRepositoryId(@Param("repositoryId") Long repositoryId, Pageable pageable);
}
