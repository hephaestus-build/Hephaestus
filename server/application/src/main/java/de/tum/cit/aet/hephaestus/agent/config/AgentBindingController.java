package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * A workspace's agents: what model, with what limits, runs each {@link AgentPurpose}. There is
 * one assignment per purpose and data-handling tier; {@code PUT} is idempotent within that pair.
 */
@WorkspaceScopedController
@RequestMapping("/agents")
@Tag(name = "Agents", description = "Workspace-scoped per-purpose agent configuration")
@RequiredArgsConstructor
@Validated
public class AgentBindingController {

    private final AgentBindingService agentBindingService;

    @GetMapping
    @Operation(summary = "List the workspace's agents and how each is configured")
    @ApiResponse(responseCode = "200", description = "Bindings returned")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<List<AgentBindingDTO>> listAgents(WorkspaceContext workspaceContext) {
        List<AgentBindingDTO> bindings = agentBindingService.getBindings(workspaceContext).stream()
                .map(binding -> AgentBindingDTO.from(binding, agentBindingService.isReady(binding)))
                .toList();
        return ResponseEntity.ok(bindings);
    }

    @PutMapping("/{purpose}")
    @Operation(summary = "Configure the agent for one purpose")
    @ApiResponse(
            responseCode = "200",
            description = "Binding saved",
            content = @Content(schema = @Schema(implementation = AgentBindingDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Model not found",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "The model is undeclared, or declared as another tier than this slot (problem type agent-binding-slot-mismatch, property declaredTier)",
            content = @Content(schema = @Schema(hidden = true)))
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "AGENT_BINDING")
    public ResponseEntity<AgentBindingDTO> configureAgent(
            WorkspaceContext workspaceContext,
            @PathVariable AgentPurpose purpose,
            @RequestParam(defaultValue = "UNDECLARED") DataHandlingTier dataHandlingTier,
            @Valid @RequestBody AgentBindingRequestDTO request) {
        WorkspaceAgentBinding binding =
                agentBindingService.upsertBinding(workspaceContext, purpose, dataHandlingTier, request);
        return ResponseEntity.ok(AgentBindingDTO.from(binding, agentBindingService.isReady(binding)));
    }

    @DeleteMapping("/{purpose}")
    @Operation(summary = "Remove the agent for one purpose (turn it off)")
    @ApiResponse(responseCode = "204", description = "Binding removed")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "AGENT_BINDING")
    public ResponseEntity<Void> deleteAgent(
            WorkspaceContext workspaceContext,
            @PathVariable AgentPurpose purpose,
            @RequestParam(defaultValue = "UNDECLARED") DataHandlingTier dataHandlingTier) {
        agentBindingService.deleteBinding(workspaceContext, purpose, dataHandlingTier);
        return ResponseEntity.noContent().build();
    }
}
