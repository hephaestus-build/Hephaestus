package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE entry point for the sync-observability live-push stream, with {@code SyncController}'s workspace scoping and
 * authorization. Each {@code sync} event carries one {@link SyncEventHintDTO}, which the spec documents as the
 * stream's event, so the clients get its type.
 */
@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/sync/events")
@RequireAtLeastWorkspaceAdmin
@Tag(name = "Sync")
public class SyncEventsController {

    private final SyncEventHub hub;

    public SyncEventsController(SyncEventHub hub) {
        this.hub = hub;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            operationId = "streamSyncEvents",
            summary = "Live-push stream of sync-state invalidation hints for this workspace")
    @ApiResponse(
            responseCode = "200",
            description = "A server-sent event stream; each `sync` event's data is one hint",
            content =
                    @Content(
                            mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(implementation = SyncEventHintDTO.class)))
    public SseEmitter events(WorkspaceContext workspace, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return hub.subscribe(workspace.id());
    }
}
