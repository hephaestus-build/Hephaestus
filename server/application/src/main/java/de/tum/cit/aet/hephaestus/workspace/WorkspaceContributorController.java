package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceContributorDTO;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Provider activity is not account access: hiding an SCM actor never grants or revokes a role. */
@WorkspaceScopedController
@RequestMapping("/contributors")
@RequiredArgsConstructor
public class WorkspaceContributorController {
    private final WorkspaceMembershipService contributors;

    @GetMapping
    @SecurityRequirements
    public ResponseEntity<List<WorkspaceContributorDTO>> listWorkspaceContributors(
            WorkspaceContext context,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(contributors
                .listMembers(context.id(), PageRequest.of(page, Math.clamp(size, 1, 100), Sort.by("createdAt")))
                .map(WorkspaceContributorDTO::from)
                .getContent());
    }

    @PatchMapping("/{userId}/hidden")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ROLE")
    public ResponseEntity<WorkspaceContributorDTO> updateMemberVisibility(
            WorkspaceContext context, @PathVariable Long userId, @RequestParam boolean hidden) {
        return ResponseEntity.ok(
                WorkspaceContributorDTO.from(contributors.updateMemberVisibility(context.id(), userId, hidden)));
    }
}
