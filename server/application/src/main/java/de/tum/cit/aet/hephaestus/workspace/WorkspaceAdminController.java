package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.workspace.dto.AdminWorkspaceViewDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instance-admin workspaces overview, guarded by the namespaced {@code app_admin} authority. Returns
 * metadata only (no tenant content); private user content is reached through audited read-only user views.
 * Thin adapter over {@link WorkspaceAdminService}.
 */
@RestController
@RequestMapping("/admin/workspaces")
@Tag(name = "Admin", description = "Instance-admin account management")
@PreAuthorize("hasAuthority('app_admin')")
@WorkspaceAgnostic("Instance-admin cross-tenant overview; authorized by app_admin, not workspace context")
public class WorkspaceAdminController {

    private final WorkspaceAdminService workspaceAdminService;

    public WorkspaceAdminController(WorkspaceAdminService workspaceAdminService) {
        this.workspaceAdminService = workspaceAdminService;
    }

    @GetMapping
    @Operation(summary = "List all workspaces (metadata only)", operationId = "adminListWorkspaces")
    public ResponseEntity<List<AdminWorkspaceViewDTO>> list() {
        return ResponseEntity.ok(workspaceAdminService.listAll());
    }
}
