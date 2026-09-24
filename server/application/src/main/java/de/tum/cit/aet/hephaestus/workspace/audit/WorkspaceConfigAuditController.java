package de.tum.cit.aet.hephaestus.workspace.audit;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntryViewDTO;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilterParams;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditQuery;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Workspace-admin history of admin configuration changes. Lives in {@code workspace} rather than
 * beside its instance-admin twin because it needs {@code WorkspaceContext}, and {@code core} must not
 * depend on {@code workspace}; it reaches the trail through the {@code config-audit-spi} port.
 *
 * <p>{@code entityType} + {@code entityId} + {@code changedKey} are the per-resource and per-control
 * history contract that the settings pages filter on.
 */
@WorkspaceScopedController
@RequestMapping("/config-audit")
@Tag(name = "Config Audit", description = "Workspace-scoped history of admin configuration changes")
@RequiredArgsConstructor
public class WorkspaceConfigAuditController {

    private final ConfigAuditQuery configAuditQuery;

    @GetMapping
    @Operation(
            summary = "List this workspace's admin configuration changes (paged, newest first)",
            operationId = "listWorkspaceConfigAuditEvents")
    @RequireAtLeastWorkspaceAdmin
    public ResponseEntity<PageResponseDTO<ConfigAuditEntryViewDTO>> list(
            WorkspaceContext workspaceContext,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @ParameterObject ConfigAuditFilterParams filter) {
        // The workspace comes from the resolved context, never from a request param — it is the
        // tenancy boundary, so a caller must not be able to widen it.
        return ResponseEntity.ok(PageResponseDTO.from(configAuditQuery.listForWorkspace(
                workspaceContext.id(), filter.toFilter(), ConfigAuditFilterParams.pageable(page, size))));
    }
}
