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

@WorkspaceAgnostic(
        "Memberships scoped through organization_id; organization is global, membership filtered by workspace context")
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
    @Modifying
    @Transactional
    @Query("DELETE FROM OrganizationMembership m WHERE m.organizationId = :orgId")
    void deleteByOrganizationId(@Param("orgId") Long organizationId);

    @Modifying
    @Transactional
    @Query("DELETE FROM OrganizationMembership m WHERE m.organizationId = :orgId AND m.userId IN :userIds")
    void deleteByOrganizationIdAndUserIdIn(
            @Param("orgId") Long organizationId, @Param("userIds") Collection<Long> userIds);
}
