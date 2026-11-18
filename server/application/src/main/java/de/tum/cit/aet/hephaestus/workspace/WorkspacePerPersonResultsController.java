package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Whether workspace admins see practice results per person. Every member may read it, because it decides who sees
 * their results.
 */
@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/per-person-results")
@RequiredArgsConstructor
public class WorkspacePerPersonResultsController {
    private final WorkspaceSettingsService settings;
    private final WorkspaceRepository workspaces;

    public record PerPersonResultsDTO(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Whether workspace admins see practice results per person")
            boolean enabled,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Whether the caller may turn it on: only a workspace owner may")
            boolean canTurnOn) {}

    public record UpdatePerPersonResultsDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
            Boolean enabled) {}

    @GetMapping
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(
            operationId = "getPerPersonResults",
            summary = "Whether workspace admins see practice results per person")
    public PerPersonResultsDTO get(WorkspaceContext context) {
        return dto(context, workspaces.findById(context.id()).orElseThrow());
    }

    @PatchMapping
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_VISIBILITY")
    @Operation(
            operationId = "updatePerPersonResults",
            summary = "Turn per-person results for admins on or off",
            description = "Only a workspace owner turns it on. Any workspace admin turns it off.")
    @ApiResponse(responseCode = "200", description = "The setting after the change")
    @ApiResponse(
            responseCode = "403",
            description = "The caller is not an owner and asked to turn it on",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public PerPersonResultsDTO update(WorkspaceContext context, @Valid @RequestBody UpdatePerPersonResultsDTO body) {
        return dto(context, settings.updatePerPersonResults(context, body.enabled()));
    }

    private static PerPersonResultsDTO dto(WorkspaceContext context, Workspace workspace) {
        return new PerPersonResultsDTO(workspace.isPerPersonResultsForAdmins(), context.hasRole(WorkspaceRole.OWNER));
    }
}
