package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.web.ClientInputs;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** The developer's questions to Hephaestus in a workspace: asking, and stopping a turn. */
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/conversation")
@Tag(name = "Conversation", description = "The developer's conversation with Hephaestus")
@PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
@RequiredArgsConstructor
public class ConversationQuestionController {

    private final AppConversationQuestions questions;
    private final ClientInputs inputs;
    private final ConversationTimeline timeline;
    private final ConversationTurnService turns;
    private final ConversationRequestScope scope;

    @PostMapping("/turns")
    @Operation(
            summary = "Ask Hephaestus",
            description =
                    "Stores the question before anything is shown and queues a turn. A question while Hephaestus "
                            + "answers waits as a follow-up. Repeat the original body and Idempotency-Key after a lost response.")
    @ApiResponse(responseCode = "200", description = "The stored question and its turn")
    @ApiResponse(
            responseCode = "422",
            description = "The key was already used with a different body",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The request is still running (idempotency-key-in-flight), Hephaestus declines the person "
                    + "(conversation-refused), or the conversation cannot accept the question (conversation-rejected)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ConversationSubmissionDTO submitConversationTurn(
            WorkspaceContext workspace,
            @RequestHeader(ClientInputs.HEADER) UUID key,
            @Valid @RequestBody ConversationTurnRequestDTO request) {
        long workspaceId = workspace.id();
        var person = PersonAct.fromPrincipal();
        var fingerprint = new ClientInputs.FingerprintInput("submitConversationTurn", request, List.of());
        return scope.write(account -> {
            var result = inputs.create(workspaceId, person, key, fingerprint, () -> {
                var submission = questions.ask(workspaceId, account, request, TimeOrderedIds.next());
                return new ClientInputs.Result(
                        submission.turn().getId(), submission.question().getId());
            });
            return new ConversationSubmissionDTO(
                    timeline.turn(workspaceId, account, result.id()),
                    timeline.entry(workspaceId, account, result.relatedId()));
        });
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
            throw new InvalidInputException("A turn can only be stopped.");
        }
        return scope.write(account -> {
            timeline.requireOwnedTurn(workspace.id(), account, turnId);
            turns.stop(workspace.id(), turnId);
            return timeline.turn(workspace.id(), account, turnId);
        });
    }
}
