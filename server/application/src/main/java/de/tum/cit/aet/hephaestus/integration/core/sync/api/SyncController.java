package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** HTTP API for integration sync status and administration. */
@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/connections")
@RequireAtLeastWorkspaceAdmin
@Tag(name = "Sync", description = "Integration sync observability and manual controls")
@Validated
public class SyncController {

    private final SyncStatusService syncStatusService;

    public SyncController(SyncStatusService syncStatusService) {
        this.syncStatusService = syncStatusService;
    }

    @GetMapping("/{connectionId}/sync")
    @Operation(summary = "Unified sync status for one connection")
    public ResponseEntity<ConnectionSyncStatusDTO> getConnectionSyncStatus(
            WorkspaceContext workspace, @PathVariable Long connectionId) {
        return ResponseEntity.ok(syncStatusService.getStatus(workspace.id(), connectionId));
    }

    @GetMapping("/{connectionId}/sync/resources")
    @Operation(summary = "Per-resource sync state (repos / channels / collections) for one connection")
    public ResponseEntity<List<SyncResourceStateDTO>> listConnectionSyncResources(
            WorkspaceContext workspace, @PathVariable Long connectionId) {
        return ResponseEntity.ok(syncStatusService.getResources(workspace.id(), connectionId));
    }

    @GetMapping("/{connectionId}/sync/jobs")
    @Operation(summary = "Paginated sync job history for one connection")
    public ResponseEntity<PageResponseDTO<SyncJobDTO>> listConnectionSyncJobs(
            WorkspaceContext workspace,
            @PathVariable Long connectionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int pageSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(
                safePage,
                pageSize,
                Sort.by("createdAt").descending().and(Sort.by("id").descending()));
        return ResponseEntity.ok(
                PageResponseDTO.from(syncStatusService.getJobs(workspace.id(), connectionId, pageable)));
    }

    /**
     * Trigger a manual sync. Idempotent-absorb: a connection that already has an active job answers
     * 200 with that job rather than erroring, so a double-click "Sync now" is harmless.
     */
    @PostMapping("/{connectionId}/sync/jobs")
    @Operation(summary = "Trigger a manual sync or backfill", operationId = "triggerSyncJob")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "A new sync job was created and dispatched",
                content = @Content(schema = @Schema(implementation = SyncJobDTO.class))),
        @ApiResponse(
                responseCode = "200",
                description = "Idempotent-absorb: a same-type job was already running and is returned unchanged",
                content = @Content(schema = @Schema(implementation = SyncJobDTO.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Missing or invalid request body (e.g. absent sync type)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Connection not found in this workspace",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description =
                        "Connection is not ACTIVE, a different sync type is already running, or manual sync is unsupported for the kind",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "503",
                description = "The server is too busy to dispatch the sync; retry later",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    @AuditExempt(reason = "sync job control, not configuration; each run is its own sync_job record")
    public ResponseEntity<SyncJobDTO> triggerConnectionSyncJob(
            WorkspaceContext workspace,
            @PathVariable Long connectionId,
            @RequestBody @Valid @NotNull TriggerSyncJobRequestDTO body) {
        Long triggeredByUserId = SecurityUtils.getCurrentAccountId().orElse(null);
        SyncStatusService.TriggerOutcome outcome =
                syncStatusService.triggerSync(workspace.id(), connectionId, body.type(), triggeredByUserId);
        return outcome.created()
                ? ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome.job())
                : ResponseEntity.ok(outcome.job());
    }

    @PatchMapping("/{connectionId}/sync/jobs/{jobId}")
    @Operation(summary = "Update a running sync job")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Cancellation requested; the running job stops cooperatively",
                content = @Content(schema = @Schema(implementation = SyncJobDTO.class))),
        @ApiResponse(
                responseCode = "400",
                description = "The update does not request cancellation",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Job not found in this workspace, or not owned by this connection",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Job is already in a terminal status",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    })
    @AuditExempt(reason = "sync job control, not configuration; each run is its own sync_job record")
    public ResponseEntity<SyncJobDTO> updateConnectionSyncJob(
            WorkspaceContext workspace,
            @PathVariable Long connectionId,
            @PathVariable Long jobId,
            @RequestBody @Valid @NotNull UpdateSyncJobRequestDTO body) {
        SyncJobDTO job = syncStatusService.cancelJob(workspace.id(), connectionId, jobId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/catalog")
    @Operation(summary = "Every integration kind this workspace could connect, joined against existing connections")
    public ResponseEntity<List<IntegrationCatalogEntryDTO>> getIntegrationCatalog(WorkspaceContext workspace) {
        return ResponseEntity.ok(syncStatusService.catalog(workspace.id()));
    }
}
