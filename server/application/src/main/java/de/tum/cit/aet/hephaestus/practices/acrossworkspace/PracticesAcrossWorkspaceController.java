package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.core.SmallCounts;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceTilesDTO;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Practices across the workspace, read by the calling developer: everyone, the caller included, inside a count, and
 * the caller's own figures beside the workspace's. Like the Practice profile, the route takes no user parameter.
 */
@ConditionalOnServerRole
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practices")
@PreAuthorize("@workspaceSecure.isMember()")
@RequiredArgsConstructor
public class PracticesAcrossWorkspaceController {
    private final PracticesAcrossWorkspaceService service;

    @GetMapping("/workspace-overview")
    @Operation(
            operationId = "getPracticeWorkspaceOverview",
            summary = "How the workspace's developers split across practice groups and practices, the reader marked",
            description = "Counts developers, never names them. A split shows all its parts or only its total, and a"
                    + " count of 1 to "
                    + (SmallCounts.MINIMUM - 1)
                    + " developers shows as null. The splits count every developer's current standing once and take"
                    + " no window.")
    public PracticesAcrossWorkspaceDTO overview(WorkspaceContext ws) {
        return service.read(ws);
    }

    @GetMapping("/tiles")
    @Operation(
            operationId = "getPracticeWorkspaceTiles",
            summary = "The reader's figures over one window beside the workspace's middle half",
            description = "A middle half shows when at least "
                    + SmallCounts.MINIMUM
                    + " developers besides the reader are counted in the window and its ends do not meet.")
    public PracticesAcrossWorkspaceTilesDTO tiles(
            WorkspaceContext ws, @RequestParam PracticesAcrossWorkspaceWindow window) {
        return service.readTiles(ws, window);
    }
}
