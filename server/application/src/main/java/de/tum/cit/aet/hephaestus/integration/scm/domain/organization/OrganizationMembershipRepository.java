package de.tum.cit.aet.hephaestus.integration.scm.domain.organization;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organization memberships are shared mirror rows with no {@code workspace_id} (ADR 0077). Each read is keyed by
 * the id of an organization that the caller resolved through its workspace or the sync.
 */
public interface OrganizationMembershipRepository
        extends JpaRepository<OrganizationMembership, OrganizationMembershipId> {
    @Query("SELECT m.userId FROM OrganizationMembership m WHERE m.organizationId = :orgId")
    List<Long> findUserIdsByOrganizationId(@Param("orgId") Long organizationId);

    List<OrganizationMembership> findByOrganizationId(Long organizationId);

    Optional<OrganizationMembership> findByOrganizationIdAndUserId(Long organizationId, Long userId);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO organization_membership (organization_id, user_id, role)
        VALUES (:orgId, :userId, :#{#role.name()})
        ON CONFLICT (organization_id, user_id)
        DO UPDATE SET role = EXCLUDED.role
        """, nativeQuery = true)
    void upsertMembership(
            @Param("orgId") Long organizationId,
            @Param("userId") Long userId,
            @Param("role") OrganizationMemberRole role);

    /**
     * Drop the whole membership mirror for one organization — the org-tier half of
     * {@code workspace.ScmWorkspaceContentEraser}, run only after the eraser has established that no
     * non-purged workspace is still bound to this organization. The organization row itself
     * is global and survives; only the person↔org edges this instance mirrored are removed.
     * Idempotent.
     */
    @WorkspaceAgnostic(
            "Erasing the last workspace bound to a shared organization drops that organization's membership mirror")
    @Modifying
    @Transactional
    @Query("DELETE FROM OrganizationMembership m WHERE m.organizationId = :orgId")
    void deleteByOrganizationId(@Param("orgId") Long organizationId);

    @WorkspaceAgnostic(
            "Membership sync and webhooks remove the members that the provider no longer lists from one shared"
                    + " organization")
    @Modifying
    @Transactional
    @Query("DELETE FROM OrganizationMembership m WHERE m.organizationId = :orgId AND m.userId IN :userIds")
    void deleteByOrganizationIdAndUserIdIn(
            @Param("orgId") Long organizationId, @Param("userIds") Collection<Long> userIds);
}
