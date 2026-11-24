package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The workspace's own monthly cap on spend through its own connected provider. The host-funded purse
 * has its own cap at {@code PUT /admin/workspaces/{workspaceSlug}/llm/budget}, which this cannot touch.
 */
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/llm/budget")
@Tag(name = "LLM Usage", description = "Per-workspace LLM spend rollup and budget status")
@Validated
public class WorkspaceLlmBudgetController {

    private final LlmUsageService llmUsageService;

    public WorkspaceLlmBudgetController(LlmUsageService llmUsageService) {
        this.llmUsageService = llmUsageService;
    }

    @PutMapping
    @Operation(
            summary = "Set or clear this workspace's monthly cap on its own-provider LLM spend",
            operationId = "updateWorkspaceLlmBudget")
    @ApiResponse(responseCode = "204", description = "Cap updated")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_OWN_PROVIDER_LLM_BUDGET")
    public ResponseEntity<Void> updateBudget(
            WorkspaceContext workspaceContext, @Valid @RequestBody UpdateLlmBudgetRequestDTO request) {
        llmUsageService.updateOwnProviderBudget(workspaceContext.id(), request.monthlyBudgetUsd());
        return ResponseEntity.noContent().build();
    }
}
