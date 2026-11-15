package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.trace.dto.ItemTraceDTO;
import de.tum.cit.aet.hephaestus.practices.trace.dto.OwnItemTraceDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/practices/items")
@Tag(name = "Practice review trace")
@RequiredArgsConstructor
public class ItemTraceController {
    private final ItemTraceQueryService query;
    private final OwnItemTraceQueryService own;

    @GetMapping("/{itemId}/trace")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getItemTrace", summary = "Trace reviews of an item")
    public ItemTraceDTO trace(WorkspaceContext ws, @PathVariable long itemId) {
        return query.trace(ws.id(), itemId);
    }

    @GetMapping("/{itemId}/own-trace")
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(operationId = "getOwnItemTrace", summary = "Trace your results from an item review")
    public OwnItemTraceDTO own(WorkspaceContext ws, @PathVariable long itemId, @RequestParam UUID reviewId) {
        return own.own(ws.id(), itemId, reviewId);
    }
}
