package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.dto.AssignRoleRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceMembershipDTO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WorkspaceScopedController
@RequestMapping("/members")
@RequiredArgsConstructor
public class WorkspaceMembershipController {

    private final WorkspaceMembershipService workspaceMembershipService;
    private final UserRepository userRepository;

    /** One representative membership, with the account's effective workspace role. */
    @GetMapping("/me")
    @SecurityRequirements
    public ResponseEntity<WorkspaceMembershipDTO> getCurrentUserMembership(WorkspaceContext context) {
        User currentUser = requireCurrentUser();
        WorkspaceMembership membership = workspaceMembershipService.getMembership(context.id(), currentUser.getId());
        WorkspaceRole effectiveRole = context.roles().stream()
                .reduce((first, next) -> first.isAtLeast(next) ? first : next)
                .orElse(membership.getRole());
        if (effectiveRole != WorkspaceRole.OWNER
                && effectiveRole != WorkspaceRole.ADMIN
                && SecurityUtils.isSuperAdmin()) {
            effectiveRole = WorkspaceRole.ADMIN;
        }
        return ResponseEntity.ok(WorkspaceMembershipDTO.from(membership, effectiveRole));
    }

    @GetMapping
    @SecurityRequirements
    public ResponseEntity<List<WorkspaceMembershipDTO>> listMembers(
            WorkspaceContext context,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Results per page, capped at 100") @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, pageSize, Sort.by("createdAt").ascending());

        List<WorkspaceMembershipDTO> memberships = workspaceMembershipService
                .listMembers(context.id(), pageable)
                .map(WorkspaceMembershipDTO::from)
                .getContent();

        return ResponseEntity.ok(memberships);
    }

    @GetMapping("/{userId}")
    @SecurityRequirements
    public ResponseEntity<WorkspaceMembershipDTO> getMember(WorkspaceContext context, @PathVariable Long userId) {
        WorkspaceMembership membership = requireMembership(context.id(), userId);
        return ResponseEntity.ok(WorkspaceMembershipDTO.from(membership));
    }

    @PostMapping("/assign")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ROLE")
    public ResponseEntity<WorkspaceMembershipDTO> assignRole(
            WorkspaceContext context, @Valid @RequestBody AssignRoleRequestDTO request) {
        WorkspaceMembership membership =
                workspaceMembershipService.assignRole(context.id(), request.userId(), request.role());
        return ResponseEntity.ok(WorkspaceMembershipDTO.from(membership));
    }

    @PatchMapping("/{userId}/hidden")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ROLE")
    public ResponseEntity<WorkspaceMembershipDTO> updateMemberVisibility(
            WorkspaceContext context,
            @PathVariable Long userId,
            @Parameter(description = "Whether to exclude the member from leaderboard rankings") @RequestParam
                    boolean hidden) {
        WorkspaceMembership membership =
                workspaceMembershipService.updateMemberVisibility(context.id(), userId, hidden);
        return ResponseEntity.ok(WorkspaceMembershipDTO.from(membership));
    }

    @DeleteMapping("/{userId}")
    @ApiResponse(responseCode = "204", description = "Membership removed")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ROLE")
    public ResponseEntity<Void> removeMember(WorkspaceContext context, @PathVariable Long userId) {
        workspaceMembershipService.removeMembership(context.id(), userId);
        return ResponseEntity.noContent().build();
    }

    private User requireCurrentUser() {
        return userRepository
                .getCurrentUser()
                .orElseThrow(() -> new AccessForbiddenException("User not authenticated"));
    }

    private WorkspaceMembership requireMembership(Long workspaceId, Long userId) {
        return workspaceMembershipService
                .findMembership(workspaceId, userId)
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceMembership", userId));
    }
}
