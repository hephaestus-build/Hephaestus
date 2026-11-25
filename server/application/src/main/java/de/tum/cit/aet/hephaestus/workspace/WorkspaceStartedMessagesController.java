package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Whether Hephaestus may start messages to people in the workspace: agreed check-ins and unprompted updates (ADR
 * 0139). Answers and the results a person asked for arrive either way.
 */
@WorkspaceScopedController
@TeamOnly
@ConditionalOnServerRole
@RequestMapping("/started-messages")
@RequiredArgsConstructor
public class WorkspaceStartedMessagesController {
    private final WorkspaceSettingsService settings;
    private final WorkspaceRepository workspaces;

    public record StartedMessagesDTO(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Whether Hephaestus may start messages to people in the workspace")
            boolean enabled) {}

    public record UpdateStartedMessagesDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
            Boolean enabled) {}

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    @Operation(
            operationId = "getStartedMessages",
            summary = "Whether Hephaestus may start messages to people in the workspace")
    public StartedMessagesDTO get(WorkspaceContext context) {
        return new StartedMessagesDTO(
                workspaces.findById(context.id()).orElseThrow().isStartedMessagesEnabled());
    }

    @PatchMapping
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_FEATURES")
    @Operation(
            operationId = "updateStartedMessages",
            summary = "Let Hephaestus start messages to people in the workspace, or stop it")
    @ApiResponse(responseCode = "200", description = "The setting after the change")
    public StartedMessagesDTO update(WorkspaceContext context, @Valid @RequestBody UpdateStartedMessagesDTO body) {
        return new StartedMessagesDTO(
                settings.updateStartedMessages(context.id(), body.enabled()).isStartedMessagesEnabled());
    }
}
