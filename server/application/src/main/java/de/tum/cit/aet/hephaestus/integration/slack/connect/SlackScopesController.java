package de.tum.cit.aet.hephaestus.integration.slack.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Tells a workspace admin which scopes the workspace's Slack token lacks. A token keeps the scopes of its own
 * authorization, so a Slack app installed before a scope was added fails that scope's calls with {@code missing_scope}
 * until an admin reconnects Slack. Slack, not a stored copy, is asked each time, so the answer holds after a reconnect.
 */
@WorkspaceScopedController
@RequestMapping("/slack/scopes")
@RequireAtLeastWorkspaceAdmin
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
@Tag(name = "Slack connection", description = "The scopes of the workspace's Slack connection")
public class SlackScopesController {

    private final SlackCredentialProvider credentials;
    private final SlackOAuthClient slack;

    public SlackScopesController(SlackCredentialProvider credentials, SlackOAuthClient slack) {
        this.credentials = credentials;
        this.slack = slack;
    }

    @GetMapping
    @Operation(
            operationId = "getSlackScopes",
            summary = "List the Slack scopes that Hephaestus requests and the workspace's Slack token does not hold")
    public ResponseEntity<SlackScopesDTO> getSlackScopes(WorkspaceContext workspace) {
        String token = credentials
                .resolve(new IntegrationRef(IntegrationKind.SLACK, workspace.id(), null))
                .filter(BearerToken.class::isInstance)
                .map(bundle -> ((BearerToken) bundle).token())
                .orElseThrow(() -> new SlackSendException(workspace.id(), "", "no_active_slack_connection"));
        Set<String> granted;
        try {
            granted = slack.grantedScopes(token);
        } catch (SlackOAuthException e) {
            // The Slack routes share one problem shape: SlackChannelControllerAdvice maps SlackSendException.
            throw new SlackSendException(workspace.id(), "", String.valueOf(e.getMessage()), e);
        }
        return ResponseEntity.ok(new SlackScopesDTO(SlackConnectionStrategy.DEFAULT_SCOPES.stream()
                .filter(scope -> !granted.contains(scope))
                .sorted()
                .toList()));
    }
}
