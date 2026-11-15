package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Workspace is the tenant root - queries manage workspaces themselves")
public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /** A replacement source connection has an initial roster of its own. */
    @Query(value = """
            SELECT w.members_synced_at IS NULL OR EXISTS (
                SELECT 1 FROM connection c WHERE c.workspace_id = w.id
                  AND c.kind IN ('GITHUB', 'GITLAB') AND c.state IN ('ACTIVE', 'SUSPENDED')
                  AND c.created_at > w.members_synced_at)
            FROM workspace w WHERE w.id = :workspaceId
            """, nativeQuery = true)
    boolean isInitialMemberImport(@Param("workspaceId") long workspaceId);

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery$WorkspaceView(
            w.workspaceSlug, w.displayName)
        FROM Workspace w
        WHERE w.id = :id
          AND w.status <> de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus.PURGED
        """)
    Optional<AccountMembershipQuery.WorkspaceView> findNotificationViewById(@Param("id") long id);

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.workspace.WorkspaceListData(
            w.id, w.workspaceSlug, w.displayName, w.status, w.accountLogin, w.createdAt,
            w.features.practicesEnabled, w.publicActivityEnabled)
        FROM Workspace w
        WHERE w.id IN :ids
        """)
    List<WorkspaceListData> findListDataByIds(@Param("ids") Collection<Long> ids);

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.workspace.WorkspaceIdData(w.id)
        FROM Workspace w
        WHERE w.workspaceSlug = :slug
          AND w.status = de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus.ACTIVE
        """)
    Optional<WorkspaceIdData> findActiveIdBySlug(@Param("slug") String slug);

    /** Serialize the before-snapshot and update so concurrent admin changes cannot revert fields or misstate the audit trail. */
    @WorkspaceAgnostic("Locking read of the tenant root itself, by its own id")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT w FROM Workspace w WHERE w.id = :id")
    Optional<Workspace> findByIdForUpdate(@Param("id") Long id);

    /** Lock by slug in one query; a separate lookup could return stale managed state on the locking read. */
    @WorkspaceAgnostic("Locking read of the tenant root itself, by its own slug")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT w FROM Workspace w WHERE w.workspaceSlug = :slug")
    Optional<Workspace> findByWorkspaceSlugForUpdate(@Param("slug") String slug);

    /**
     * Reverse-lookup a workspace by its GitHub App installation id, through the {@code Connection}
     * whose {@code kind='GITHUB'} and {@code instance_key=installationId}. At most one connection
     * holds an installation ({@code uq_connection_one_github_installation}); uninstalled ones stay
     * as history, so the holder wins and otherwise the latest to have held it — which a provider
     * uninstall that follows a disconnect still finds.
     */
    default Optional<Workspace> findByInstallationId(Long installationId) {
        if (installationId == null) {
            return Optional.empty();
        }
        return findWorkspaceIdsByGitHubInstallationInstanceKey(installationId.toString()).stream()
                .findFirst()
                .flatMap(this::findById);
    }

    default Optional<Workspace> findByInstallationIdForUpdate(Long installationId) {
        if (installationId == null) {
            return Optional.empty();
        }
        return findWorkspaceIdsByGitHubInstallationInstanceKey(installationId.toString()).stream()
                .findFirst()
                .flatMap(this::findByIdForUpdate);
    }

    default Optional<Workspace> findActiveByInstallationIdForUpdate(Long installationId) {
        return findByInstallationIdForUpdate(installationId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE);
    }

    @Query("""
        SELECT c.workspace.id
        FROM Connection c
        WHERE c.kind = de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind.GITHUB
          AND c.instanceKey = :instanceKey
        ORDER BY CASE
                   WHEN c.state = de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState.UNINSTALLED THEN 1
                   ELSE 0
                 END,
                 c.id DESC
        """)
    List<Long> findWorkspaceIdsByGitHubInstallationInstanceKey(@Param("instanceKey") String instanceKey);

    Optional<Workspace> findByRepositoriesToMonitor_NameWithOwner(String nameWithOwner);

    Optional<Workspace> findByOrganizationId(Long organizationId);

    /** At most one workspace binds an organization ({@code uc_workspaceorganization_id_col}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT w FROM Workspace w WHERE w.organizationId = :organizationId")
    Optional<Workspace> findByOrganizationIdForUpdate(@Param("organizationId") Long organizationId);

    /**
     * The workspace's group and repository selection as stored. A scalar projection, so it is read
     * from the database even when this transaction already manages a Workspace loaded before a lock
     * was taken.
     */
    @Query("SELECT w.accountLogin AS accountLogin, w.repositorySelection AS repositorySelection"
            + " FROM Workspace w WHERE w.id = :id")
    Optional<MonitorPolicy> findMonitorPolicy(@Param("id") Long id);

    interface MonitorPolicy {
        String getAccountLogin();

        @Nullable
        RepositorySelection getRepositorySelection();
    }

    Optional<Workspace> findByAccountLoginIgnoreCase(String login);

    List<Workspace> findAllByAccountLoginIgnoreCase(String login);

    Optional<Workspace> findByWorkspaceSlug(String workspaceSlug);

    boolean existsByWorkspaceSlug(String workspaceSlug);

    boolean existsByOrganizationId(Long organizationId);

    boolean existsByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * Org-tier orphan check for the SCM mirror eraser: how many workspaces OTHER than {@code
     * excludedWorkspaceId} are still bound to this organization and not already purged. A non-zero
     * count would mean another tenant still holds a lawful basis for the org-tier mirror ({@code
     * team}, {@code team_membership}, {@code organization_membership}), so it must survive the
     * erasing workspace.
     *
     * <p><b>Defensive only.</b> Unlike {@code repository}, which many workspaces genuinely share,
     * the organization binding is exclusive: {@code Workspace.organizationId} is a
     * {@code @OneToOne} over a {@code unique = true} {@code organization_id} column, so an
     * organization backs at most one workspace and this count is always 0 in production. It is kept
     * so the SCM mirror eraser stays correct if that 1:1 mapping is ever relaxed.
     */
    @Query("""
        SELECT COUNT(w) FROM Workspace w
        WHERE w.organizationId = :organizationId
          AND w.id <> :excludedWorkspaceId
          AND w.status <> de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus.PURGED
        """)
    long countOtherActiveWorkspacesForOrganization(
            @Param("organizationId") Long organizationId, @Param("excludedWorkspaceId") Long excludedWorkspaceId);

    List<Workspace> findByStatus(Workspace.WorkspaceStatus status);

    @Query("SELECT w.id FROM Workspace w WHERE w.status = :status ORDER BY w.id")
    List<Long> findIdsByStatus(@Param("status") Workspace.WorkspaceStatus status);

    @Query("""
        SELECT w FROM Workspace w WHERE w.workspaceSlug = :slug AND w.publicActivityEnabled = true
            AND w.status = de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus.ACTIVE
        """)
    Optional<Workspace> findPublicActivityWorkspace(@Param("slug") String slug);
}
