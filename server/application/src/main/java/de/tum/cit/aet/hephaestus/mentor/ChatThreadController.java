package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Workspace-scoped CRUD over mentor chat threads. SSE streaming for new turns lives on the
 * separate {@code MentorChatController} under {@code /mentor/chat}; this endpoint serves the
 * thread listing and the on-load history fetch.
 *
 * <p>Ownership is service-enforced: only the user who created a thread can read or delete it,
 * even other workspace admins cannot — mentor conversations are private by design.
 */
@WorkspaceScopedController
@RequestMapping("/mentor/threads")
@Tag(name = "Mentor Threads", description = "Workspace-scoped mentor chat threads")
@RequiredArgsConstructor
public class ChatThreadController {

    private final ChatThreadService chatThreadService;

    @GetMapping
    @Operation(summary = "List the current user's mentor threads in this workspace")
    @ApiResponse(responseCode = "200", description = "Threads returned, newest first")
    @PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
    public ResponseEntity<List<ChatThreadSummaryDTO>> listThreads(WorkspaceContext workspaceContext) {
        return ResponseEntity.ok(chatThreadService.listSummariesForCurrentUser(workspaceContext.id()));
    }

    @GetMapping("/{threadId}")
    @Operation(summary = "Get a mentor thread with its full message history")
    @ApiResponse(responseCode = "200", description = "Thread + messages returned")
    @ApiResponse(
            responseCode = "404",
            description = "Thread not found OR not owned by current user",
            content = @Content(schema = @Schema(hidden = true)))
    @PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
    public ResponseEntity<ChatThreadDetailDTO> getThread(
            WorkspaceContext workspaceContext, @PathVariable UUID threadId) {
        ChatThreadService.ThreadDetail detail =
                chatThreadService.loadOwnedThreadDetail(workspaceContext.id(), threadId);
        return ResponseEntity.ok(
                new ChatThreadDetailDTO(detail.id(), detail.title(), detail.createdAt(), detail.messages()));
    }

    @DeleteMapping("/{threadId}")
    @Operation(summary = "Delete a mentor thread (cascades to messages, votes, parts)")
    @ApiResponse(responseCode = "204", description = "Thread deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Thread not found OR not owned by current user",
            content = @Content(schema = @Schema(hidden = true)))
    @PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
    public ResponseEntity<Void> deleteThread(WorkspaceContext workspaceContext, @PathVariable UUID threadId) {
        chatThreadService.deleteOwnedThread(workspaceContext.id(), threadId);
        return ResponseEntity.noContent().build();
    }
}
