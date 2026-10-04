package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceTilesDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Practices across the workspace, read by the calling developer: everyone, the caller included, inside a count, and
 * the caller's own figures beside the workspace's. Like the practice profile there is no user parameter on this
 * route.
 */
@WorkspaceScopedController
@PreAuthorize("@workspaceSecure.isMember()")
@RequestMapping("/practices/workspace-overview")
@Tag(name = "Practices Across The Workspace", description = "How the workspace's developers split, the reader marked")
@RequiredArgsConstructor
public class PracticesAcrossWorkspaceController {

    private final PracticesAcrossWorkspaceService service;

    @GetMapping
    @Operation(
            operationId = "getPracticesAcrossWorkspace",
            summary = "How the workspace's developers split across practice groups and practices, the reader marked",
            description = "Counts developers, never names them: every count shown, and every count derivable from"
                    + " them, holds none or at least "
                    + CohortPrivacyPolicy.MINIMUM_DEVELOPERS_PER_COUNT
                    + " developers, whoever reads it (CohortPrivacyPolicy). The splits count every developer's"
                    + " current standing and take no window.")
    @ApiResponse(responseCode = "200", description = "Practices across the workspace returned")
    public ResponseEntity<PracticesAcrossWorkspaceDTO> getPracticesAcrossWorkspace(WorkspaceContext context) {
        return ResponseEntity.ok(service.read(context));
    }

    @GetMapping("/tiles")
    @Operation(
            operationId = "getPracticesAcrossWorkspaceTiles",
            summary = "The reader's figures over one window beside the workspace's middle half",
            description = "A middle half shows only when at least "
                    + CohortPrivacyPolicy.MINIMUM_DEVELOPERS_FOR_MIDDLE_HALF
                    + " developers are counted in the window, whoever reads it; each window is checked on its own.")
    @ApiResponse(responseCode = "200", description = "The tiles for the window returned")
    public ResponseEntity<PracticesAcrossWorkspaceTilesDTO> getPracticesAcrossWorkspaceTiles(
            WorkspaceContext context, @RequestParam PracticesAcrossWorkspaceWindow window) {
        return ResponseEntity.ok(service.readTiles(context, window));
    }
}
