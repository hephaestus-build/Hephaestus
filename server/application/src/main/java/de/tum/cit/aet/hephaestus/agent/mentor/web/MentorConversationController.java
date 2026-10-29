package de.tum.cit.aet.hephaestus.agent.mentor.web;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorAgentProperties;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryPageDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorReadDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorThreadDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
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
@RequestMapping("/mentor")
@Tag(name = "Mentor", description = "The developer's conversation with Hephaestus")
@PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
@RequiredArgsConstructor
public class MentorConversationController {

    private final MentorTimeline timeline;
    private final MentorAdmission admission;
    private final MentorConversationService conversations;
    private final MentorEntryService entries;
    private final MentorTurnService turns;
    private final MentorAgentProperties properties;
    private final CurrentDeveloperLookup developer;

    @GetMapping("/conversation")
    @Operation(summary = "The developer's conversation: what runs now, its threads and how far it reached")
    public MentorConversationDTO getMentorConversation(WorkspaceContext workspace) {
        return timeline.conversation(workspace.id(), account());
    }

    @PutMapping("/conversation/read")
    @Operation(
            summary = "Mark the conversation read",
            description = "Records how far the developer read, for every device. The marker keeps the latest time:"
                    + " an earlier one changes nothing.")
    @ApiResponse(responseCode = "200", description = "The marker after the request: this time or a later one")
    public MentorReadDTO markMentorConversationRead(
            WorkspaceContext workspace, @Valid @RequestBody MentorReadRequestDTO request) {
        return new MentorReadDTO(conversations.markRead(workspace.id(), account(), request.at()));
    }

    @GetMapping("/entries")
    @Operation(summary = "A page of top-level messages, oldest first; pass before=<seq> for older ones")
    public MentorEntryPageDTO listMentorEntries(
            WorkspaceContext workspace,
            @RequestParam(required = false) @Nullable Long before,
            @RequestParam(defaultValue = "" + MentorTimeline.DEFAULT_PAGE) int limit) {
        return timeline.entries(workspace.id(), account(), before, limit);
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
    public MentorThreadDTO getMentorThread(
            WorkspaceContext workspace,
            @PathVariable UUID entryId,
            @RequestParam(required = false) @Nullable Long before,
            @RequestParam(defaultValue = "" + MentorTimeline.DEFAULT_PAGE) int limit) {
        return timeline.thread(workspace.id(), account(), entryId, before, limit);
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
                    "Hephaestus declines the developer (mentor-refused), too many follow-ups wait, the requestId was "
                            + "used for another message, or the parent cannot have a thread (mentor-rejected)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public MentorSubmissionDTO submitMentorTurn(
            WorkspaceContext workspace, @Valid @RequestBody MentorTurnRequestDTO request) {
        long workspaceId = workspace.id();
        long account = account();
        if (request.text().length() > properties.maxPromptChars()) {
            throw new IllegalArgumentException(
                    "Your message is too long. The maximum is " + properties.maxPromptChars() + " characters.");
        }
        admission.refusal(workspaceId, account).ifPresent(refusal -> {
            throw new MentorRefusedException(refusal);
        });
        MentorConversation main = conversations.findOrCreateMain(workspaceId, account);
        UUID conversationId = main.getId();
        UUID parentEntryId = request.parentEntryId();
        if (parentEntryId != null) {
            timeline.requireOwnedEntry(workspaceId, account, parentEntryId);
            conversationId =
                    conversations.openThread(workspaceId, parentEntryId).getId();
        }
        MentorTurnService.Submission submission = turns.submit(
                workspaceId,
                conversationId,
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        request.text(),
                        request.requestId().toString(),
                        null,
                        request.pageContext()));
        return new MentorSubmissionDTO(
                timeline.turn(workspaceId, account, submission.turn().getId()),
                timeline.entry(workspaceId, account, submission.question().getId()));
    }

    @PatchMapping("/turns/{turnId}/status")
    @Operation(
            summary = "Stop a turn",
            description =
                    "A queued turn never runs and keeps its question. A running turn keeps what Hephaestus already "
                            + "wrote. Stopping an ended turn changes nothing. A turn asked in Slack can be stopped here.")
    public MentorTurnDTO updateMentorTurnStatus(
            WorkspaceContext workspace,
            @PathVariable UUID turnId,
            @Valid @RequestBody MentorTurnStatusRequestDTO request) {
        if (request.status() != MentorTurn.Status.STOPPED) {
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
    public MentorEntryDTO setMentorVote(
            WorkspaceContext workspace, @PathVariable UUID entryId, @Valid @RequestBody MentorVoteRequestDTO request) {
        return vote(workspace.id(), entryId, request.vote());
    }

    @DeleteMapping("/entries/{entryId}/vote")
    @Operation(summary = "Clear the vote on one of Hephaestus's messages")
    public MentorEntryDTO clearMentorVote(WorkspaceContext workspace, @PathVariable UUID entryId) {
        return vote(workspace.id(), entryId, null);
    }

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
    public ResponseEntity<Void> deleteMentorEntry(WorkspaceContext workspace, @PathVariable UUID entryId) {
        timeline.requireOwnedEntry(workspace.id(), account(), entryId);
        entries.deleteMessage(workspace.id(), entryId);
        return ResponseEntity.noContent().build();
    }

    private MentorEntryDTO vote(long workspaceId, UUID entryId, MentorEntry.@Nullable Vote vote) {
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
