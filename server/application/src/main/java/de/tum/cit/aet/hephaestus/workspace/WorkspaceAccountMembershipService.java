package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots.AccountMembershipSnapshot;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.exception.InsufficientWorkspacePermissionsException;
import de.tum.cit.aet.hephaestus.workspace.exception.LastOwnerRemovalException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkspaceAccountMembershipService {
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final AccountIdentityQuery identities;
    private final ConfigAuditPort audit;

    @Transactional(readOnly = true)
    public List<WorkspaceAccountMembership> list(Long workspaceId) {
        return memberships.findByWorkspace_Id(workspaceId);
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceAccountMembership> find(Long workspaceId, Long accountId) {
        return memberships.findByWorkspace_IdAndAccountId(workspaceId, accountId);
    }

    @Transactional
    public WorkspaceAccountMembership assign(Long workspaceId, Long accountId, WorkspaceRole role) {
        var workspace = lock(workspaceId);
        requirePermission(workspace, role);
        identities
                .accountForUpdate(accountId)
                .filter(AccountIdentityQuery.AccountView::active)
                .orElseThrow(() -> new EntityNotFoundException("Account", accountId));
        var membership = memberships
                .findByWorkspace_IdAndAccountId(workspaceId, accountId)
                .orElse(null);
        var before = membership == null ? null : AccountMembershipSnapshot.of(membership);
        if (membership == null) {
            membership = new WorkspaceAccountMembership();
            membership.setWorkspace(workspace);
            membership.setAccountId(accountId);
        } else {
            requirePermission(
                    workspace,
                    membership.getSource() == WorkspaceAccountMembership.Source.DIRECTORY
                            ? WorkspaceRole.OWNER
                            : membership.getRole());
            if (role != WorkspaceRole.OWNER) requireNotLastOwner(workspace, membership);
        }
        membership.setRole(role);
        membership.setSource(WorkspaceAccountMembership.Source.MANUAL);
        membership.setSuspended(false);
        var saved = memberships.save(membership);
        if (before == null)
            audit.record(ConfigAuditEntry.created(
                    ConfigAuditEntityType.WORKSPACE_ROLE, accountId, workspaceId, AccountMembershipSnapshot.of(saved)));
        else
            audit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.WORKSPACE_ROLE,
                    accountId,
                    workspaceId,
                    before,
                    AccountMembershipSnapshot.of(saved)));
        return saved;
    }

    @Transactional
    public void suspend(Long workspaceId, Long accountId) {
        var workspace = lock(workspaceId);
        var membership = memberships
                .findByWorkspace_IdAndAccountId(workspaceId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceAccountMembership", accountId));
        requirePermission(workspace, membership.getRole());
        requireNotLastOwner(workspace, membership);
        var before = AccountMembershipSnapshot.of(membership);
        membership.setSuspended(true);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                accountId,
                workspaceId,
                before,
                AccountMembershipSnapshot.of(membership)));
    }

    private Workspace lock(Long workspaceId) {
        return workspaces
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
    }

    private void requirePermission(Workspace workspace, WorkspaceRole role) {
        var context = WorkspaceContextHolder.getContext();
        var current = SecurityUtils.getCurrentAccountId()
                .flatMap(accountId -> memberships.findByWorkspace_IdAndAccountId(workspace.getId(), accountId))
                .filter(member -> !member.isSuspended());
        boolean owner =
                current.map(member -> member.getRole() == WorkspaceRole.OWNER).orElse(false);
        boolean admin =
                current.map(member -> member.getRole() == WorkspaceRole.ADMIN).orElse(false)
                        || SecurityUtils.isSuperAdmin();
        if (context == null
                || !workspace.getId().equals(context.id())
                || !(owner || (admin && role != WorkspaceRole.OWNER)))
            throw new InsufficientWorkspacePermissionsException(
                    workspace.getWorkspaceSlug(), "You cannot manage the " + role + " role");
    }

    private void requireNotLastOwner(Workspace workspace, WorkspaceAccountMembership membership) {
        if (!membership.isSuspended()
                && membership.getRole() == WorkspaceRole.OWNER
                && memberships.countByWorkspace_IdAndRoleAndSuspendedFalse(workspace.getId(), WorkspaceRole.OWNER) <= 1)
            throw new LastOwnerRemovalException(workspace.getWorkspaceSlug());
    }
}
