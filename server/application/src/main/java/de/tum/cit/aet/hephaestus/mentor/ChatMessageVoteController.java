package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Up/down votes on mentor assistant messages. Ownership runs through the thread — the
 * service first verifies the caller owns the thread the message belongs to, then upserts.
 */
@WorkspaceScopedController
@RequestMapping("/mentor/threads/{threadId}/messages/{messageId}/vote")
@Tag(name = "Mentor Votes", description = "Up/down votes on mentor assistant messages")
@RequiredArgsConstructor
public class ChatMessageVoteController {

    private final ChatThreadService chatThreadService;
    private final ChatMessageVoteService chatMessageVoteService;

    @PostMapping
    @Operation(summary = "Upsert a vote on an assistant message")
    @ApiResponse(responseCode = "200", description = "Vote recorded")
    @ApiResponse(
            responseCode = "404",
            description = "Thread or message not found (or not owned by current user)",
            content = @Content(schema = @Schema(hidden = true)))
    @PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
    public ResponseEntity<ChatMessageVoteDTO> vote(
            WorkspaceContext workspaceContext,
            @PathVariable UUID threadId,
            @PathVariable UUID messageId,
            @Valid @RequestBody ChatMessageVoteRequestDTO body) {
        // Ownership: thread must be owned by current user before any vote write is permitted.
        chatThreadService.getOwnedThread(workspaceContext.id(), threadId);
        ChatMessageVote vote =
                chatMessageVoteService.upsert(threadId, messageId, Boolean.TRUE.equals(body.isUpvoted()));
        return ResponseEntity.ok(ChatMessageVoteDTO.from(vote));
    }

    @DeleteMapping
    @Operation(summary = "Remove a vote on an assistant message (idempotent)")
    @ApiResponse(responseCode = "204", description = "Vote removed or did not exist")
    @ApiResponse(
            responseCode = "404",
            description = "Thread or message not found (or not owned by current user)",
            content = @Content(schema = @Schema(hidden = true)))
    @PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
    public ResponseEntity<Void> removeVote(
            WorkspaceContext workspaceContext, @PathVariable UUID threadId, @PathVariable UUID messageId) {
        chatThreadService.getOwnedThread(workspaceContext.id(), threadId);
        chatMessageVoteService.delete(threadId, messageId);
        return ResponseEntity.noContent().build();
    }
}
