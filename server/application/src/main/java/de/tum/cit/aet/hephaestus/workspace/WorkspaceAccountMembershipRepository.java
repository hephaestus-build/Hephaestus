package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Human memberships are queried by explicit workspace or authenticated account ID")
public interface WorkspaceAccountMembershipRepository extends JpaRepository<WorkspaceAccountMembership, Long> {
    Optional<WorkspaceAccountMembership> findByWorkspace_IdAndAccountId(Long workspaceId, Long accountId);

    List<WorkspaceAccountMembership> findByWorkspace_Id(Long workspaceId);

    @Query(
            "SELECT m FROM WorkspaceAccountMembership m JOIN FETCH m.workspace WHERE m.accountId = :accountId AND m.suspended = false")
    List<WorkspaceAccountMembership> findActiveByAccountId(@Param("accountId") Long accountId);

    long countByWorkspace_IdAndRoleAndSuspendedFalse(Long workspaceId, WorkspaceRole role);

    void deleteAllByWorkspace_Id(Long workspaceId);

    void deleteAllByAccountId(Long accountId);
}
