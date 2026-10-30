package de.tum.cit.aet.hephaestus.agent.catalog;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Workspace-admin management of models on "your AI provider", plus the available-models projection a
 * workspace admin binds a Task's model from.
 */
@WorkspaceScopedController
@RequestMapping("/llm")
@Tag(
        name = "Workspace LLM",
        description = "Workspace-scoped \"bring your own\" AI provider connections, models and settings")
@RequiredArgsConstructor
@Validated
public class WorkspaceLlmModelController {

    private final WorkspaceLlmModelService modelService;
    private final LlmCallPoolService callPools;
    private final LlmModelCapabilityProbe capabilityProbe;

    @PostMapping("/connections/{connectionId}/models")
    @Operation(summary = "Create a model on your AI provider", operationId = "workspaceCreateLlmModel")
    @ApiResponse(responseCode = "201", description = "Model created; URL in the Location header")
    @ApiResponse(
            responseCode = "404",
            description = "AI provider connection not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "A model with this slug or upstream model id already exists on the connection",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_LLM_MODEL")
    public ResponseEntity<WorkspaceLlmModelDTO> create(
            WorkspaceContext workspaceContext,
            @PathVariable Long connectionId,
            @Valid @RequestBody CreateWorkspaceLlmModelRequestDTO request) {
        WorkspaceLlmModel created = modelService.create(workspaceContext, connectionId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/workspaces/{workspaceSlug}/llm/models/{id}")
                .buildAndExpand(workspaceContext.slug(), created.getId())
                .toUri();
        return ResponseEntity.created(location).body(toDTO(created));
    }

    @GetMapping("/models")
    @Operation(summary = "List models on your AI provider", operationId = "workspaceListLlmModels")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<WorkspaceLlmModelDTO>> list(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(
                modelService.list(workspaceContext).stream().map(this::toDTO).toList());
    }

    @GetMapping("/models/{id}")
    @Operation(summary = "Get a model on your AI provider", operationId = "workspaceGetLlmModel")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = WorkspaceLlmModelDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<WorkspaceLlmModelDTO> get(WorkspaceContext workspaceContext, @PathVariable Long id) {
        return ResponseEntity.ok(toDTO(modelService.get(workspaceContext, id)));
    }

    @PatchMapping("/models/{id}")
    @Operation(summary = "Update a model on your AI provider", operationId = "workspaceUpdateLlmModel")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = WorkspaceLlmModelDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "Another model on the connection already uses this upstream model id",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_LLM_MODEL")
    public ResponseEntity<WorkspaceLlmModelDTO> update(
            WorkspaceContext workspaceContext,
            @PathVariable Long id,
            @Valid @RequestBody UpdateWorkspaceLlmModelRequestDTO request) {
        return ResponseEntity.ok(toDTO(modelService.update(workspaceContext, id, request)));
    }

    @PutMapping("/models/{id}/capabilities")
    @Operation(
            summary = "Probe the model's forced tool-choice capability",
            operationId = "workspaceProbeLlmModelCapabilities")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_LLM_MODEL")
    public ResponseEntity<LlmModelCapabilitiesDTO> probeCapabilities(
            WorkspaceContext workspaceContext, @PathVariable Long id) {
        var model = modelService.get(workspaceContext, id);
        var connection = model.getConnection();
        var target = new LlmProbeTarget(
                connection.getBaseUrl(), connection.getAuthMode(), connection.getApiKey(), connection.getApiProtocol());
        var result = capabilityProbe.probe(target, model.getUpstreamModelId());
        modelService.recordCapability(workspaceContext, id, model.getUpstreamModelId(), target, result);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/models/{id}")
    @Operation(summary = "Remove a model on your AI provider", operationId = "workspaceDeleteLlmModel")
    @ApiResponse(responseCode = "204", description = "Model removed")
    @ApiResponse(
            responseCode = "404",
            description = "Model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description = "Cannot delete a model still bound to an agent configuration",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_LLM_MODEL")
    public ResponseEntity<Void> delete(WorkspaceContext workspaceContext, @PathVariable Long id) {
        modelService.delete(workspaceContext, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/available-models")
    @Operation(
            summary = "List models this workspace can bind a Task to (shared + your own)",
            operationId = "workspaceListAvailableLlmModels")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<AvailableLlmModelDTO>> availableModels(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(modelService.availableModels(workspaceContext));
    }

    private WorkspaceLlmModelDTO toDTO(WorkspaceLlmModel model) {
        return WorkspaceLlmModelDTO.from(
                model, callPools.find(model.getCallPoolId()).orElse(null));
    }
}
