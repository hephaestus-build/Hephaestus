package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.conversation.ConversationDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationReadDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationThreadDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
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
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The developer's Hephaestus conversation in a workspace. Hephaestus is for the workspace's own members: instance-admin elevation
 * gives no place in it, and a conversation, message or turn of another member is not found. A read-only user view
 * reads the viewed member's conversation through the {@code GET} routes.
 */
@WorkspaceScopedController
@RequestMapping("/conversation")
@Tag(name = "Conversation", description = "The developer's conversation with Hephaestus")
@PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationTimeline timeline;
    private final ConversationService conversations;
    private final CurrentDeveloperLookup developer;

    @GetMapping
    @Operation(summary = "The developer's conversation: what runs now, its threads and how far it reached")
    public ConversationDTO getConversation(WorkspaceContext workspace) {
        return timeline.conversation(workspace.id(), CurrentConversationAccount.of(developer));
    }

    @PutMapping("/read")
    @Operation(
            summary = "Mark the conversation read",
            description = "Records how far the developer read, for every device. The marker keeps the latest time:"
                    + " an earlier one changes nothing.")
    @ApiResponse(responseCode = "200", description = "The marker after the request: this time or a later one")
    public ConversationReadDTO markConversationRead(
            WorkspaceContext workspace, @Valid @RequestBody ConversationReadRequestDTO request) {
        return new ConversationReadDTO(
                conversations.markRead(workspace.id(), CurrentConversationAccount.of(developer), request.at()));
    }

    @GetMapping("/entries")
    @Operation(summary = "A page of top-level messages, oldest first; pass nextCursor as cursor for older ones")
    public CursorPageDTO<ConversationEntryDTO> listConversationEntries(
            WorkspaceContext workspace,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + ConversationTimeline.DEFAULT_PAGE) int size) {
        return timeline.entries(workspace.id(), CurrentConversationAccount.of(developer), cursor, size);
    }

    @GetMapping("/entries/{entryId}/thread")
    @Operation(summary = "The thread on a top-level message: the message and a page of replies, oldest first")
    @ApiResponse(responseCode = "200", description = "The thread; a message without replies has no conversation")
    @ApiResponse(
            responseCode = "404",
            description = "No top-level message of the developer has this id",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ConversationThreadDTO getConversationThread(
            WorkspaceContext workspace,
            @PathVariable UUID entryId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + ConversationTimeline.DEFAULT_PAGE) int size) {
        return timeline.thread(workspace.id(), CurrentConversationAccount.of(developer), entryId, cursor, size);
    }
}
