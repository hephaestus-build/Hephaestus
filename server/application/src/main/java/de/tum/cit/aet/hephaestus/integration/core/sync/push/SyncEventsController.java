package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE entry point for the sync-observability live-push stream. Mirrors {@code
 * SyncController}'s workspace scoping and authorization but is {@link Hidden} because it carries
 * invalidation hints only, not a documented DTO contract, so it must not appear in the generated
 * OpenAPI spec.
 */
@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/sync/events")
@RequireAtLeastWorkspaceAdmin
@Tag(name = "Sync", description = "Integration sync observability live-push stream")
@Hidden
public class SyncEventsController {

    private final SyncEventHub hub;

    public SyncEventsController(SyncEventHub hub) {
        this.hub = hub;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live-push stream of sync-state invalidation hints for this workspace")
    public SseEmitter events(WorkspaceContext workspace, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return hub.subscribe(workspace.id());
    }
}
