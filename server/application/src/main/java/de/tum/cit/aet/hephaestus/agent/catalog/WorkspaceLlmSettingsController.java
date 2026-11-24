package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * What the instance's LLM policy permits in this workspace; read-only, the policy is instance-wide.
 *
 * <p>Under the workspace path because that is where the permission lives: a workspace admin may read it,
 * and the instance-wide {@code /admin/llm/settings} needs {@code app_admin}. {@code WorkspaceContextFilter}
 * matches the URL, resolves the tenant and the caller's roles, and {@link RequireAtLeastWorkspaceAdmin}
 * reads them from the holder — none of which involves this class's signature.
 *
 * <p>The handler therefore takes no {@code WorkspaceContext}: the answer does not depend on which workspace
 * asked, and there is no per-workspace form of the switch to consult ({@code WorkspaceLlmConnectionService}
 * gates on the instance value alone). {@code WorkspaceScopedControllerComplianceIntegrationTest} names this
 * handler as its one exception rather than the class dropping the meta-annotation to escape the rule —
 * escaping it would take the rule's protection off every future route that copied the trick.
 */
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/llm/settings")
@Tag(
        name = "Workspace LLM",
        description = "Workspace-scoped \"bring your own\" AI provider connections, models and settings")
@RequiredArgsConstructor
@Validated
public class WorkspaceLlmSettingsController {

    private final InstanceLlmSettingsService instanceLlmSettingsService;

    @GetMapping
    @Operation(
            summary = "Get the instance LLM policy as it applies to this workspace",
            operationId = "workspaceGetLlmSettings")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<WorkspaceLlmSettingsDTO> get() {
        return ResponseEntity.ok(
                new WorkspaceLlmSettingsDTO(instanceLlmSettingsService.get().isAllowWorkspaceConnections()));
    }
}
