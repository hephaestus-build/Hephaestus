package de.tum.cit.aet.hephaestus.core.audit.web;

import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntryViewDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilterParams;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.core.web.TotalCountDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instance-admin viewer over the config audit trail, spanning workspaces. The workspace-scoped view of
 * the same data is {@code WorkspaceConfigAuditController}.
 */
@ConditionalOnServerRole
@RestController
@RequestMapping("/admin/config-audit")
@Tag(name = "Admin", description = "Instance-admin account management")
@RequireInstanceAdmin
@RequiredArgsConstructor
public class AdminConfigAuditController {

    private final ConfigAuditQuery configAuditQuery;

    @GetMapping
    @Operation(
            summary = "List admin configuration changes across workspaces (newest first)",
            operationId = "adminListConfigAuditEvents")
    public ResponseEntity<CursorPageDTO<ConfigAuditEntryViewDTO>> list(
            @RequestParam(required = false) @Nullable Long workspaceId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "50") int size,
            @ParameterObject ConfigAuditFilterParams filter) {
        return ResponseEntity.ok(configAuditQuery.listForAdmin(workspaceId, filter.toFilter(), cursor, size));
    }

    @GetMapping("/count")
    @Operation(
            summary = "Count the configuration changes a filter matches",
            operationId = "adminCountConfigAuditEvents")
    public TotalCountDTO count(
            @RequestParam(required = false) @Nullable Long workspaceId,
            @ParameterObject ConfigAuditFilterParams filter) {
        return new TotalCountDTO(configAuditQuery.countForAdmin(workspaceId, filter.toFilter()));
    }
}
