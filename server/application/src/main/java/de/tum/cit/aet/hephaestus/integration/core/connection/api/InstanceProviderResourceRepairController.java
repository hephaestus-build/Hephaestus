package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.OffsetPageDTO;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@RequestMapping("/admin/provider-resource-repairs")
@RequireInstanceAdmin
@WorkspaceAgnostic("Instance administrators own orphan provider-resource repair and its audit")
public class InstanceProviderResourceRepairController {
    private final ProviderResourceRepairAdminService repairs;

    public InstanceProviderResourceRepairController(ProviderResourceRepairAdminService repairs) {
        this.repairs = repairs;
    }

    @GetMapping
    @Operation(operationId = "listInstanceProviderRepairs")
    public OffsetPageDTO<ProviderResourceRepairDTO> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return repairs.listInstance(page, size);
    }

    @PostMapping("/{repairId}/release")
    @RequiresRecentSignIn(refusal = "PROVIDER_RESOURCE_RELEASE")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PROVIDER_RESOURCE_RELEASE")
    @Operation(operationId = "releaseOrphanProviderRepair")
    public ResponseEntity<Void> release(
            @PathVariable UUID repairId, @Valid @RequestBody ReleaseProviderResourceRepairRequestDTO confirmation) {
        repairs.releaseOrphan(repairId, confirmation);
        return ResponseEntity.noContent().build();
    }
}
