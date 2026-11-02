package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The one sync fact that members read: an empty activity list means little while a sync runs. Jobs,
 * connections and errors stay with administrators ({@link SyncController}).
 */
@ConditionalOnServerRole
@WorkspaceScopedController
@PreAuthorize("isAuthenticated() && @workspaceSecure.isMember()")
@RequestMapping("/sync/progress")
@Tag(name = "Sync", description = "Integration sync observability and manual controls")
public class SyncProgressController {

    private final SyncStatusService syncStatusService;

    public SyncProgressController(SyncStatusService syncStatusService) {
        this.syncStatusService = syncStatusService;
    }

    @GetMapping
    @Operation(summary = "Whether a sync is running in this workspace")
    public ResponseEntity<WorkspaceSyncProgressDTO> getWorkspaceSyncProgress(WorkspaceContext workspace) {
        return ResponseEntity.ok(new WorkspaceSyncProgressDTO(syncStatusService.isSyncing(workspace.id())));
    }
}
