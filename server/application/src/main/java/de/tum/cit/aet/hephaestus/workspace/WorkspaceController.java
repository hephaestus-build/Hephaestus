package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserTeamsDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@WorkspaceScopedController
@RequiredArgsConstructor
@Validated
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceLifecycleService workspaceLifecycleService;
    private final WorkspaceRepositoryMonitorService workspaceRepositoryMonitorService;
    private final WorkspaceTeamLabelService workspaceTeamLabelService;
    private final WorkspaceQueryService workspaceQueryService;

    @GetMapping
    @Operation(summary = "Fetch a workspace by slug")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace returned",
            content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    @SecurityRequirements
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

    @PatchMapping("/public-visibility")
    @Operation(summary = "Toggle public visibility for a workspace")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace updated",
            content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_VISIBILITY")
    public ResponseEntity<WorkspaceDTO> updatePublicVisibility(
            WorkspaceContext workspaceContext, @Valid @RequestBody UpdateWorkspacePublicVisibilityRequestDTO request) {
        Workspace workspace = workspaceService.updatePublicVisibility(workspaceContext, request.isPubliclyViewable());
        return ResponseEntity.ok(workspaceQueryService.toWorkspaceDTO(workspace));
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

    @PatchMapping("/slug")
    @Operation(summary = "Rename workspace slug and create redirect")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace renamed",
            content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    @RequireWorkspaceOwner
    @AuditExempt(reason = "renames are unattributed: workspace_slug_history keeps old slugs redirecting, with no actor")
    public ResponseEntity<WorkspaceDTO> renameSlug(
            WorkspaceContext workspaceContext, @Valid @RequestBody RenameWorkspaceSlugRequestDTO request) {
        Workspace workspace = workspaceService.renameSlug(workspaceContext, request.newSlug());
        return ResponseEntity.ok(workspaceQueryService.toWorkspaceDTO(workspace));
    }

    @GetMapping("/repositories")
    @Operation(summary = "List repositories monitored by a workspace")
    @ApiResponse(
            responseCode = "200",
            description = "Repository list",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class))))
    @SecurityRequirements
    public ResponseEntity<List<String>> getRepositoriesToMonitor(WorkspaceContext workspaceContext) {
        var repositories = workspaceRepositoryMonitorService.getMonitoredRepositories(workspaceContext).stream()
                .sorted()
                .toList();
        return ResponseEntity.ok(repositories);
    }

    @PostMapping("/repositories")
    @Operation(summary = "Add a repository to a workspace monitor list")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(
            reason =
                    "which repositories are synced is ingestion scope, not a control that gates delivery; not recorded")
    public ResponseEntity<Void> addRepositoryToMonitor(
            WorkspaceContext workspaceContext, @RequestParam String nameWithOwner) {
        workspaceRepositoryMonitorService.addRepositoryToMonitor(workspaceContext, nameWithOwner);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
        return ResponseEntity.created(location).build();
    }

    @DeleteMapping("/repositories")
    @Operation(summary = "Remove a repository from a workspace monitor list")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(
            reason =
                    "which repositories are synced is ingestion scope, not a control that gates delivery; not recorded")
    public ResponseEntity<Void> removeRepositoryToMonitor(
            WorkspaceContext workspaceContext, @RequestParam String nameWithOwner) {
        workspaceRepositoryMonitorService.removeRepositoryFromMonitor(workspaceContext, nameWithOwner);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    @Operation(summary = "List workspace users and the teams they belong to")
    @SecurityRequirements
    public ResponseEntity<List<UserTeamsDTO>> getUsersWithTeams(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(workspaceTeamLabelService.getUsersWithTeams(workspaceContext));
    }

    @PostMapping("/teams/{teamId}/labels/{repositoryId}/{label}")
    @Operation(summary = "Add a repository label to a team")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "team label filter is a reporting view, not configuration that gates delivery")
    public ResponseEntity<TeamInfoDTO> addLabelToTeam(
            WorkspaceContext workspaceContext,
            @PathVariable Long teamId,
            @PathVariable Long repositoryId,
            @PathVariable String label) {
        return workspaceTeamLabelService
                .addLabelToTeam(workspaceContext, teamId, repositoryId, label)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/teams/{teamId}/labels/{labelId}")
    @Operation(summary = "Remove a repository label from a team")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "team label filter is a reporting view, not configuration that gates delivery")
    public ResponseEntity<TeamInfoDTO> removeLabelFromTeam(
            WorkspaceContext workspaceContext, @PathVariable Long teamId, @PathVariable Long labelId) {
        return workspaceTeamLabelService
                .removeLabelFromTeam(workspaceContext, teamId, labelId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
