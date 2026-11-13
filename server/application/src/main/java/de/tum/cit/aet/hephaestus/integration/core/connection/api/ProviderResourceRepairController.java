package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/connections/{connectionId}/provider-repairs")
@RequireAtLeastWorkspaceAdmin
public class ProviderResourceRepairController {
    private final ProviderResourceRepairAdminService repairs;

    public ProviderResourceRepairController(ProviderResourceRepairAdminService repairs) {
        this.repairs = repairs;
    }

    @GetMapping
    @Operation(operationId = "listConnectionProviderRepairs")
    public OffsetPageDTO<ProviderResourceRepairDTO> list(
            WorkspaceContext workspace,
            @PathVariable long connectionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return repairs.list(workspace.id(), connectionId, page, size);
    }

    @PostMapping("/{repairId}/release")
    @RequiresRecentSignIn(refusal = "PROVIDER_RESOURCE_RELEASE")
    @RequireInstanceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PROVIDER_RESOURCE_RELEASE")
    @Operation(operationId = "releaseConnectionProviderRepair")
    public ResponseEntity<Void> release(
            WorkspaceContext workspace,
            @PathVariable long connectionId,
            @PathVariable UUID repairId,
            @Valid @RequestBody ReleaseProviderResourceRepairRequestDTO confirmation) {
        repairs.release(workspace.id(), connectionId, repairId, confirmation);
        return ResponseEntity.noContent().build();
    }
}
