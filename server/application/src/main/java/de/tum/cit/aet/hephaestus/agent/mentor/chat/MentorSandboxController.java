package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Lets the webapp start the member's Heph sandbox when Heph opens, so the first message does not wait for it. */
@WorkspaceScopedController
@RequestMapping("/mentor/sandbox")
@Tag(name = "Mentor Chat", description = "Heph conversations and the sandbox they run in")
@RequiredArgsConstructor
public class MentorSandboxController {

    private final MentorSandboxPreparer mentorSandboxPreparer;

    @PostMapping
    @Operation(summary = "Prepare the current member's Heph sandbox in the background")
    @ApiResponse(
            responseCode = "202",
            description = "Accepted; the sandbox starts unless it is already warm or Heph could not answer anyway")
    @PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
    public ResponseEntity<Void> prepareMentorSandbox(WorkspaceContext workspaceContext) {
        CurrentScmIdentityHolder.getUserId()
                .ifPresent(developerId -> mentorSandboxPreparer.prepare(workspaceContext.id(), developerId));
        return ResponseEntity.accepted().build();
    }
}
