package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.auth.spi.AdminPasskeyAccess;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/passkey-policy")
@RequiredArgsConstructor
public class WorkspacePasskeyPolicyController {
    private final WorkspaceSettingsService settings;
    private final WorkspaceRepository workspaces;
    private final AdminPasskeyAccess assurance;

    public record WorkspacePasskeyPolicyDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean required,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean instanceRequired) {}

    public record UpdateWorkspacePasskeyPolicyDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
            Boolean required) {}

    @GetMapping
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(operationId = "getWorkspacePasskeyPolicy", summary = "Get workspace admin passkey policy")
    public WorkspacePasskeyPolicyDTO get(WorkspaceContext context) {
        return new WorkspacePasskeyPolicyDTO(
                workspaces.findById(context.id()).orElseThrow().isAdminPasskeyRequired(),
                assurance.workspaceAdminRequired());
    }

    @PatchMapping
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_FEATURES")
    @Operation(operationId = "updateWorkspacePasskeyPolicy", summary = "Update workspace admin passkey policy")
    public WorkspacePasskeyPolicyDTO update(
            WorkspaceContext context, @Valid @RequestBody UpdateWorkspacePasskeyPolicyDTO body) {
        assurance.requireFresh();
        settings.updatePasskeyPolicy(context.id(), body.required());
        return get(context);
    }
}
