package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceFeaturesRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspacePublicActivityRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceStatusRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceTokenRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;

@WorkspaceScopedController
@RequiredArgsConstructor
@Validated
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceLifecycleService workspaceLifecycleService;
    private final WorkspaceQueryService workspaceQueryService;

    @GetMapping
    @Operation(summary = "Fetch a workspace by slug")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace returned",
            content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    public ResponseEntity<WorkspaceDTO> getWorkspace(WorkspaceContext workspaceContext) {
        Workspace workspace = workspaceService
                .getWorkspaceBySlug(workspaceContext.slug())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceContext.slug()));
        return ResponseEntity.ok(workspaceQueryService.toWorkspaceDTO(workspace));
    }

    @PatchMapping("/status")
    @Operation(summary = "Update workspace lifecycle status")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace updated",
            content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_STATUS")
    public ResponseEntity<WorkspaceDTO> updateStatus(
            WorkspaceContext workspaceContext, @Valid @RequestBody UpdateWorkspaceStatusRequestDTO request) {
        Workspace workspace = workspaceLifecycleService.updateStatus(workspaceContext, request.status());
        return ResponseEntity.ok(workspaceQueryService.toWorkspaceDTO(workspace));
    }

    @DeleteMapping
    @Operation(summary = "Permanently delete a workspace and purge its data")
    @ApiResponse(responseCode = "204", description = "Workspace purged")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_STATUS")
    public ResponseEntity<Void> purgeWorkspace(WorkspaceContext workspaceContext) {
        workspaceLifecycleService.purgeWorkspace(workspaceContext);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/token")
    @Operation(summary = "Update workspace Personal Access Token")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace updated",
            content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_TOKEN")
    public ResponseEntity<WorkspaceDTO> updateToken(
            WorkspaceContext workspaceContext, @Valid @RequestBody UpdateWorkspaceTokenRequestDTO request) {
        Workspace workspace = workspaceService.updateToken(workspaceContext, request.personalAccessToken());
        return ResponseEntity.ok(workspaceQueryService.toWorkspaceDTO(workspace));
    }

    public record PublicActivityWorkspaceSettingsDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean allowSearchEngines) {
        static PublicActivityWorkspaceSettingsDTO from(Workspace workspace) {
            return new PublicActivityWorkspaceSettingsDTO(
                    Boolean.TRUE.equals(workspace.getPublicActivityEnabled()),
                    workspace.isPublicActivitySearchEngines());
        }
    }

    @GetMapping("/public-activity")
    @RequireAtLeastWorkspaceAdmin
    @Operation(
            operationId = "getWorkspacePublicActivitySettings",
            summary = "Get public activity and search indexing settings")
    public PublicActivityWorkspaceSettingsDTO getWorkspacePublicActivitySettings(WorkspaceContext workspaceContext) {
        var workspace =
                workspaceService.getWorkspaceBySlug(workspaceContext.slug()).orElseThrow();
        return PublicActivityWorkspaceSettingsDTO.from(workspace);
    }

    @PatchMapping("/public-activity")
    @Operation(summary = "Turn the public activity page and search indexing on or off")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace updated",
            content = @Content(schema = @Schema(implementation = PublicActivityWorkspaceSettingsDTO.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_VISIBILITY")
    public ResponseEntity<PublicActivityWorkspaceSettingsDTO> updatePublicActivity(
            WorkspaceContext workspaceContext, @Valid @RequestBody UpdateWorkspacePublicActivityRequestDTO request) {
        Workspace workspace = workspaceService.updatePublicActivity(
                workspaceContext, request.publicActivityEnabled(), request.allowSearchEngines());
        return ResponseEntity.ok(PublicActivityWorkspaceSettingsDTO.from(workspace));
    }

    @PatchMapping("/features")
    @Operation(summary = "Update workspace feature flags")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace updated",
            content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_FEATURES")
    public ResponseEntity<WorkspaceDTO> updateFeatures(
            WorkspaceContext workspaceContext, @Valid @RequestBody UpdateWorkspaceFeaturesRequestDTO request) {
        Workspace workspace = workspaceService.updateFeatures(workspaceContext, request);
        return ResponseEntity.ok(workspaceQueryService.toWorkspaceDTO(workspace));
    }
}
