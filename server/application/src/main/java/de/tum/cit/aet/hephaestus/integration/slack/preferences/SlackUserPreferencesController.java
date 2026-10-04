package de.tum.cit.aet.hephaestus.integration.slack.preferences;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

abstract class SlackUserPreferencesController {

    protected final SlackUserPreferencesService service;

    SlackUserPreferencesController(SlackUserPreferencesService service) {
        this.service = service;
    }

    /**
     * The account id from the verified cookie-JWT. Both failure branches surface the same generic 401 (rendered as
     * RFC-7807 by Spring's problemdetails support) — the subject-shape detail is an internal matter that must not
     * leak into the response body.
     */
    protected static long accountId(JwtAuthenticationToken auth) {
        if (auth == null || auth.getToken() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "You are not authenticated.");
        }
        try {
            return Long.parseLong(auth.getToken().getSubject());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "You are not authenticated.", e);
        }
    }
}

@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@RestController
@RequestMapping("/user/slack/preferences")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Slack User Preferences", description = "Current user's Slack privacy preferences")
class SlackUserPreferencesUserController extends SlackUserPreferencesController {

    SlackUserPreferencesUserController(SlackUserPreferencesService service) {
        super(service);
    }

    @GetMapping
    @Operation(summary = "Get current user's Slack preferences", operationId = "getSlackUserPreferences")
    ResponseEntity<SlackUserPreferencesDTO> get(JwtAuthenticationToken auth) {
        return ResponseEntity.ok(service.listForAccount(accountId(auth)));
    }
}

@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@WorkspaceScopedController
@RequestMapping("/slack/me/preferences")
@PreAuthorize("@workspaceSecure.isMember()")
@Tag(name = "Slack User Preferences", description = "Current user's Slack privacy preferences")
class SlackWorkspaceUserPreferencesController extends SlackUserPreferencesController {

    SlackWorkspaceUserPreferencesController(SlackUserPreferencesService service) {
        super(service);
    }

    @PatchMapping
    @Operation(
            summary = "Update current user's Slack workspace preferences",
            operationId = "updateSlackUserPreferences")
    ResponseEntity<SlackUserWorkspacePreferencesDTO> update(
            WorkspaceContext workspace,
            JwtAuthenticationToken auth,
            @Valid @RequestBody UpdateSlackUserPreferencesRequestDTO request) {
        return ResponseEntity.ok(service.updateChannelMessagesAllowed(
                workspace.id(), accountId(auth), Boolean.TRUE.equals(request.channelMessagesAllowed())));
    }
}
