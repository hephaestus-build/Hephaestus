package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationAgentProperties;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.exception.ConversationRefusedException;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationReadDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationThreadDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ConversationAdmission admission;
    private final ConversationService conversations;
    private final ConversationEntryService entries;
    private final ConversationTurnService turns;
    private final ConversationAgentProperties properties;
    private final CurrentDeveloperLookup developer;

    @GetMapping
    @Operation(summary = "The developer's conversation: what runs now, its threads and how far it reached")
    public ConversationDTO getConversation(WorkspaceContext workspace) {
        return timeline.conversation(workspace.id(), account());
    }

    @PutMapping("/read")
    @Operation(
            summary = "Mark the conversation read",
            description = "Records how far the developer read, for every device. The marker keeps the latest time:"
                    + " an earlier one changes nothing.")
    @ApiResponse(responseCode = "200", description = "The marker after the request: this time or a later one")
    public ConversationReadDTO markConversationRead(
            WorkspaceContext workspace, @Valid @RequestBody ConversationReadRequestDTO request) {
        return new ConversationReadDTO(conversations.markRead(workspace.id(), account(), request.at()));
    }

    @GetMapping("/entries")
    @Operation(summary = "A page of top-level messages, oldest first; pass nextCursor as cursor for older ones")
    public CursorPageDTO<ConversationEntryDTO> listConversationEntries(
            WorkspaceContext workspace,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + ConversationTimeline.DEFAULT_PAGE) int size) {
        return timeline.entries(workspace.id(), account(), cursor, size);
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
        return timeline.thread(workspace.id(), account(), entryId, cursor, size);
    }

    @PostMapping("/turns")
    @Operation(
            summary = "Ask Hephaestus",
            description = "Stores the question before anything is shown and queues a turn. A question while Hephaestus "
                    + "answers waits as a follow-up. Repeating a requestId returns the first submission.")
    @ApiResponse(responseCode = "200", description = "The stored question and its turn")
    @ApiResponse(
            responseCode = "409",
            description =
                    "Hephaestus declines the developer (conversation-refused), too many follow-ups wait, the requestId was "
                            + "used for another message, or the parent cannot have a thread (conversation-rejected)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ConversationSubmissionDTO submitConversationTurn(
            WorkspaceContext workspace, @Valid @RequestBody ConversationTurnRequestDTO request) {
        long workspaceId = workspace.id();
        long account = account();
        if (request.text().length() > properties.maxPromptChars()) {
            throw new IllegalArgumentException(
                    "Your message is too long. The maximum is " + properties.maxPromptChars() + " characters.");
        }
        admission.refusal(workspaceId, account).ifPresent(refusal -> {
            throw new ConversationRefusedException(refusal);
        });
        Conversation main = conversations.findOrCreateMain(workspaceId, account);
        UUID conversationId = main.getId();
        UUID parentEntryId = request.parentEntryId();
        if (parentEntryId != null) {
            timeline.requireOwnedEntry(workspaceId, account, parentEntryId);
            conversationId =
                    conversations.openThread(workspaceId, parentEntryId).getId();
        }
        ConversationTurnService.Submission submission = turns.submit(
                workspaceId,
                conversationId,
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        request.text(),
                        request.requestId().toString(),
                        null,
                        request.pageContext()));
        return new ConversationSubmissionDTO(
                timeline.turn(workspaceId, account, submission.turn().getId()),
                timeline.entry(workspaceId, account, submission.question().getId()));
    }

    @PatchMapping("/turns/{turnId}/status")
    @Operation(
            summary = "Stop a turn",
            description =
                    "A queued turn never runs and keeps its question. A running turn keeps what Hephaestus already "
                            + "wrote. Stopping an ended turn changes nothing. A turn asked in Slack can be stopped here.")
    public ConversationTurnDTO updateConversationTurnStatus(
            WorkspaceContext workspace,
            @PathVariable UUID turnId,
            @Valid @RequestBody ConversationTurnStatusRequestDTO request) {
        if (request.status() != ConversationTurn.Status.STOPPED) {
            throw new IllegalArgumentException("A turn can only be stopped.");
        }
        long account = account();
        timeline.requireOwnedTurn(workspace.id(), account, turnId);
        turns.stop(workspace.id(), turnId);
        return timeline.turn(workspace.id(), account, turnId);
    }

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
        timeline.requireOwnedEntry(workspace.id(), account(), entryId);
        entries.deleteMessage(workspace.id(), entryId);
        return ResponseEntity.noContent().build();
    }

    private ConversationEntryDTO vote(long workspaceId, UUID entryId, ConversationEntry.@Nullable Vote vote) {
        long account = account();
        timeline.requireOwnedEntry(workspaceId, account, entryId);
        entries.vote(workspaceId, entryId, vote);
        return timeline.entry(workspaceId, account, entryId);
    }

    /** The signed-in account, or the viewed account of a read-only user view. */
    private long account() {
        return developer.currentDeveloperId().orElseThrow(() -> new EntityNotFoundException("Account", "current"));
    }
}
