package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** What the developer does to one message: rate one of Hephaestus's messages, or delete one they wrote. */
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/conversation")
@Tag(name = "Conversation", description = "The developer's conversation with Hephaestus")
@PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
@RequiredArgsConstructor
public class ConversationEntryController {

    private final ConversationTimeline timeline;
    private final ConversationEntryService entries;
    private final ConversationRequestScope scope;

    @PutMapping("/entries/{entryId}/vote")
    @Operation(summary = "Rate one of Hephaestus's messages as helpful or not helpful")
    @ApiResponse(responseCode = "200", description = "The message with its vote")
    @ApiResponse(
            responseCode = "409",
            description = "The message is not one of Hephaestus's",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ConversationEntryDTO setConversationVote(
            WorkspaceContext workspace,
            @PathVariable UUID entryId,
            @Valid @RequestBody ConversationVoteRequestDTO request) {
        return vote(workspace.id(), entryId, request.vote());
    }

    @RecentSignInExempt(reason = "clears the person's own rating, which they can give again")
    @DeleteMapping("/entries/{entryId}/vote")
    @Operation(summary = "Clear the vote on one of Hephaestus's messages")
    public ConversationEntryDTO clearConversationVote(WorkspaceContext workspace, @PathVariable UUID entryId) {
        return vote(workspace.id(), entryId, null);
    }

    @RecentSignInExempt(reason = "removes the person's own conversation entry and changes no access")
    @DeleteMapping("/entries/{entryId}")
    @Operation(
            summary = "Delete a message the developer wrote",
            description =
                    "Erases its text and every summary that read it. A question Hephaestus has not answered yet is "
                            + "not answered.")
    @ApiResponse(responseCode = "204", description = "The message is deleted")
    @ApiResponse(
            responseCode = "409",
            description = "Only the developer's own questions can be deleted",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> deleteConversationEntry(WorkspaceContext workspace, @PathVariable UUID entryId) {
        return scope.execute(account -> {
            timeline.requireOwnedEntry(workspace.id(), account, entryId);
            entries.deleteMessage(workspace.id(), entryId);
            return ResponseEntity.noContent().build();
        });
    }

    private ConversationEntryDTO vote(long workspaceId, UUID entryId, ConversationEntry.@Nullable Vote vote) {
        return scope.execute(account -> {
            timeline.requireOwnedEntry(workspaceId, account, entryId);
            entries.vote(workspaceId, entryId, vote);
            return timeline.entry(workspaceId, account, entryId);
        });
    }
}
