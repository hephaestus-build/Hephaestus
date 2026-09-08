package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots.AccountMembershipSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Converts confirmed SCM membership into access only through a verified immutable identity link. */
@Service
@RequiredArgsConstructor
public class WorkspaceAccountMembershipSync {
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final AccountIdentityQuery identities;
    private final ConfigAuditPort audit;

    @Transactional
    public void synchronize(Workspace workspace, Map<Long, WorkspaceRole> desiredActorRoles) {
        Objects.requireNonNull(desiredActorRoles, "Only a complete SCM membership snapshot can change access");
        workspaces.findByIdForUpdate(workspace.getId()).orElseThrow();
        Map<Long, WorkspaceRole> desiredAccounts = new HashMap<>();
        for (var entry : desiredActorRoles.entrySet()) {
            users.findById(entry.getKey())
                    .flatMap(this::accountId)
                    .ifPresent(accountId -> desiredAccounts.merge(
                            accountId,
                            entry.getValue() == WorkspaceRole.OWNER ? WorkspaceRole.ADMIN : entry.getValue(),
                            (first, second) -> first.isAtLeast(second) ? first : second));
        }
        for (var existing : memberships.findByWorkspace_Id(workspace.getId())) {
            var desired = desiredAccounts.remove(existing.getAccountId());
            if (existing.getSource() != WorkspaceAccountMembership.Source.SCM || existing.isSuspended()) continue;
            // The final owner must transfer ownership explicitly; a source cannot orphan a workspace.
            boolean lastOwner = existing.getRole() == WorkspaceRole.OWNER
                    && memberships.countByWorkspace_IdAndRoleAndSuspendedFalse(workspace.getId(), WorkspaceRole.OWNER)
                            <= 1;
            if (lastOwner && desired != WorkspaceRole.OWNER) continue;
            var before = AccountMembershipSnapshot.of(existing);
            if (desired == null) {
                memberships.delete(existing);
                audit.record(ConfigAuditEntry.deleted(
                        ConfigAuditEntityType.WORKSPACE_ROLE, existing.getAccountId(), workspace.getId(), before));
            } else {
                existing.setRole(desired);
                audit.record(ConfigAuditEntry.updated(
                        ConfigAuditEntityType.WORKSPACE_ROLE,
                        existing.getAccountId(),
                        workspace.getId(),
                        before,
                        AccountMembershipSnapshot.of(existing)));
            }
        }
        desiredAccounts.forEach(
                (accountId, role) -> create(workspace, accountId, role, WorkspaceAccountMembership.Source.SCM));
    }

    /** Workspace creation uses a verified account, never a provider display name or cached actor ID. */
    @Transactional
    public void createForActor(Workspace workspace, User actor, WorkspaceRole role) {
        createForActor(workspace, actor, role, true);
    }

    /** An installation may precede sign-in, and an organization is not a human account. */
    @Transactional
    public void createForInstallation(Workspace workspace, User actor) {
        createForActor(workspace, actor, WorkspaceRole.OWNER, false);
    }

    private void createForActor(Workspace workspace, User actor, WorkspaceRole role, boolean requireOwner) {
        workspaces.findByIdForUpdate(workspace.getId()).orElseThrow();
        var accountId = accountId(actor);
        if (accountId.isEmpty() && role == WorkspaceRole.OWNER && requireOwner)
            throw new IllegalStateException(
                    "Connect the workspace owner's SCM identity to an active account before creating the workspace");
        accountId.ifPresent(id -> {
            if (memberships
                    .findByWorkspace_IdAndAccountId(workspace.getId(), id)
                    .isEmpty()) create(workspace, id, role, WorkspaceAccountMembership.Source.MANUAL);
        });
    }

    private Optional<Long> accountId(User user) {
        if (user.getType() != User.Type.USER) return Optional.empty();
        return identities.resolveActiveAccountId(
                Objects.requireNonNull(user.getProvider().getId()),
                user.getNativeId().toString(),
                null);
    }

    private void create(
            Workspace workspace, Long accountId, WorkspaceRole role, WorkspaceAccountMembership.Source source) {
        if (identities
                .accountForUpdate(accountId)
                .filter(AccountIdentityQuery.AccountView::active)
                .isEmpty()) return;
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(accountId);
        membership.setRole(role);
        membership.setSource(source);
        memberships.save(membership);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                accountId,
                workspace.getId(),
                AccountMembershipSnapshot.of(membership)));
    }
}
