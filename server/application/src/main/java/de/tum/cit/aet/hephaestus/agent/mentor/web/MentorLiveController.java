package de.tum.cit.aet.hephaestus.agent.mentor.web;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live stream of the developer's Heph conversation. Hidden from the OpenAPI spec because a stream has no response
 * schema there; its events carry {@code MentorConversation}, {@code MentorTurn} and {@code MentorEntry}, which the
 * spec documents through the conversation routes. See {@link MentorLiveHub}.
 */
@ConditionalOnServerRole
@WorkspaceScopedController
@RequestMapping("/mentor/events")
@PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
@Hidden
public class MentorLiveController {

    private final MentorLiveHub hub;

    public MentorLiveController(MentorLiveHub hub) {
        this.hub = hub;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMentorEvents(WorkspaceContext workspace, HttpServletResponse response) {
        long developerId = CurrentScmIdentityHolder.getUserId()
                .orElseThrow(() -> new EntityNotFoundException("User", "current authenticated user"));
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        long accountId = SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new EntityNotFoundException("Account", "current authenticated account"));
        return hub.subscribe(workspace.id(), accountId, developerId, MentorConversationController.actors());
    }
}
