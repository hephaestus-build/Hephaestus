package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.dto.AssignRoleRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceAccountMembershipDTO;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@WorkspaceScopedController
@RequestMapping("/members")
@RequiredArgsConstructor
public class WorkspaceMembershipController {
    private final WorkspaceAccountMembershipService memberships;
    private final AccountIdentityQuery identities;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkspaceAccountMembershipDTO> getCurrentUserMembership(WorkspaceContext context) {
        var accountId = SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new AccessForbiddenException("Sign in to view your membership"));
        var membership = memberships
                .find(context.id(), accountId)
                .filter(member -> !member.isSuspended())
                .orElse(null);
        if (SecurityUtils.isSuperAdmin() && (membership == null || membership.getRole() == WorkspaceRole.MEMBER)) {
            var name = identities
                    .account(accountId)
                    .map(AccountIdentityQuery.AccountView::displayName)
                    .orElse("Instance administrator");
            return ResponseEntity.ok(new WorkspaceAccountMembershipDTO(
                    accountId,
                    name,
                    WorkspaceRole.ADMIN,
                    membership == null ? null : membership.getSource(),
                    false,
                    membership == null ? null : membership.getCreatedAt(),
                    CurrentScmIdentityHolder.getLogin().orElse(null)));
        }
        if (membership == null) throw new EntityNotFoundException("WorkspaceAccountMembership", accountId);
        return ResponseEntity.ok(toDto(membership));
    }

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<WorkspaceAccountMembershipDTO>> listMembers(WorkspaceContext context) {
        return ResponseEntity.ok(
                memberships.list(context.id()).stream().map(this::toDto).toList());
    }

    @GetMapping("/{accountId}")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<WorkspaceAccountMembershipDTO> getMember(
            WorkspaceContext context, @PathVariable Long accountId) {
        return ResponseEntity.ok(toDto(memberships
                .find(context.id(), accountId)
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceAccountMembership", accountId))));
    }

    @PostMapping("/assign")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ROLE")
    public ResponseEntity<WorkspaceAccountMembershipDTO> assignRole(
            WorkspaceContext context, @Valid @RequestBody AssignRoleRequestDTO request) {
        return ResponseEntity.ok(toDto(memberships.assign(context.id(), request.accountId(), request.role())));
    }

    @DeleteMapping("/{accountId}")
    @ApiResponse(responseCode = "204", description = "Access suspended; an explicit role assignment restores it")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ROLE")
    public ResponseEntity<Void> removeMember(WorkspaceContext context, @PathVariable Long accountId) {
        memberships.suspend(context.id(), accountId);
        return ResponseEntity.noContent().build();
    }

    private WorkspaceAccountMembershipDTO toDto(WorkspaceAccountMembership member) {
        var name = identities
                .account(member.getAccountId())
                .map(AccountIdentityQuery.AccountView::displayName)
                .orElse("Deleted account");
        return new WorkspaceAccountMembershipDTO(
                member.getAccountId(),
                name,
                member.getRole(),
                member.getSource(),
                member.isSuspended(),
                member.getCreatedAt(),
                SecurityUtils.getCurrentAccountId()
                        .filter(member.getAccountId()::equals)
                        .flatMap(id -> CurrentScmIdentityHolder.getLogin())
                        .orElse(null));
    }
}
