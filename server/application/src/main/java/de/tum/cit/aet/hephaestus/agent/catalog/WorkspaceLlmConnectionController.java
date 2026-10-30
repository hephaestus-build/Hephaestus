package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Workspace-admin management of "your AI provider" — a workspace's own, tenant-scoped LLM connection.
 * Mutations are gated on the instance-wide {@code allow_workspace_connections} switch inside
 * {@link WorkspaceLlmConnectionService}.
 */
@WorkspaceScopedController
@RequestMapping("/llm/connections")
@Tag(
        name = "Workspace LLM",
        description = "Workspace-scoped \"bring your own\" AI provider connections, models and settings")
@RequiredArgsConstructor
@Validated
public class WorkspaceLlmConnectionController {

    private final WorkspaceLlmConnectionService connectionService;

    @GetMapping
    @Operation(summary = "List your AI provider connections", operationId = "workspaceListLlmConnections")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<WorkspaceLlmConnectionDTO>> list(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(connectionService.list(workspaceContext).stream()
                .map(WorkspaceLlmConnectionDTO::from)
                .toList());
    }

    @PostMapping
    @Operation(summary = "Connect your own AI provider", operationId = "workspaceCreateLlmConnection")
    @ApiResponse(responseCode = "201", description = "Connection created; URL in the Location header")
    @ApiResponse(
            responseCode = "409",
            description = "A connection with this slug already exists in this workspace",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_LLM_CONNECTION")
    public ResponseEntity<WorkspaceLlmConnectionDTO> create(
            WorkspaceContext workspaceContext, @Valid @RequestBody CreateWorkspaceLlmConnectionRequestDTO request) {
        WorkspaceLlmConnection created = connectionService.create(workspaceContext, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(WorkspaceLlmConnectionDTO.from(created));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update your AI provider connection", operationId = "workspaceUpdateLlmConnection")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = WorkspaceLlmConnectionDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Connection not found",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_LLM_CONNECTION")
    public ResponseEntity<WorkspaceLlmConnectionDTO> update(
            WorkspaceContext workspaceContext,
            @PathVariable Long id,
            @Valid @RequestBody UpdateWorkspaceLlmConnectionRequestDTO request) {
        return ResponseEntity.ok(
                WorkspaceLlmConnectionDTO.from(connectionService.update(workspaceContext, id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove your AI provider connection", operationId = "workspaceDeleteLlmConnection")
    @ApiResponse(responseCode = "204", description = "Connection removed")
    @ApiResponse(
            responseCode = "404",
            description = "Connection not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "Cannot delete a connection still referenced by one or more models",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_LLM_CONNECTION")
    public ResponseEntity<Void> delete(WorkspaceContext workspaceContext, @PathVariable Long id) {
        connectionService.delete(workspaceContext, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/probe")
    @Operation(summary = "Test your AI provider connection", operationId = "workspaceProbeLlmConnection")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(reason = "tests a stored credential; stores no configuration")
    public ResponseEntity<WorkspaceLlmProbeResultDTO> probe(WorkspaceContext workspaceContext, @PathVariable Long id) {
        return ResponseEntity.ok(connectionService.probe(workspaceContext, id));
    }
}
