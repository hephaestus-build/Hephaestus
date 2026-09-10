package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDeletionGuard;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots.AccountMembershipSnapshot;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
class WorkspaceOwnerDeletionAdapter implements AccountDeletionGuard {
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final ConfigAuditPort audit;

    @Override
    public void beforeDeletion(Long accountId) {
        // Deletion already holds the account. Never wait on a workspace held by a grant waiting
        // for that account; a conflicting request rolls back and can be retried without losing an owner.
        for (var membership : memberships.findActiveByAccountId(accountId).stream()
                .filter(member -> member.getRole() == WorkspaceRole.OWNER)
                .sorted(Comparator.comparing(member -> member.getWorkspace().getId()))
                .toList()) {
            Workspace workspace;
            try {
                workspace = workspaces
                        .findByIdForUpdateNowait(membership.getWorkspace().getId())
                        .orElseThrow();
            } catch (PessimisticLockingFailureException conflict) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Workspace access is being changed. Retry account deletion after that change completes.",
                        conflict);
            }
            if (workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE
                    && memberships.countByWorkspace_IdAndRoleAndSuspendedFalse(workspace.getId(), WorkspaceRole.OWNER)
                            <= 1)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Transfer ownership or suspend workspace " + workspace.getWorkspaceSlug()
                                + " before deleting your account");
            var before = AccountMembershipSnapshot.of(membership);
            membership.setSuspended(true);
            audit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.WORKSPACE_ROLE,
                    accountId,
                    workspace.getId(),
                    before,
                    AccountMembershipSnapshot.of(membership)));
        }
    }
}
