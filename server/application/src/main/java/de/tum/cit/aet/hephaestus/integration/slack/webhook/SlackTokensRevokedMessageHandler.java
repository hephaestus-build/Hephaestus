package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackUninstallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Handles Slack token revocation from the unified Slack event stream.
 *
 * <p>{@code tokens_revoked} distinguishes revoked token types ({@code event.tokens.bot} vs
 * {@code event.tokens.oauth}): a member revoking their personal Sign-in-with-Slack authorization fires this event
 * with only {@code oauth} entries while the app remains installed and the bot token stays valid. Workspace teardown
 * (connection flip + irreversible purge of consented channel data) therefore runs only when a <em>bot</em> token was
 * revoked; user-token-only revocations are a no-op here (no user tokens are stored).
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackTokensRevokedMessageHandler extends AbstractSlackEnvelopeHandler {

    private static final Logger log = LoggerFactory.getLogger(SlackTokensRevokedMessageHandler.class);

    private final SlackUninstallService uninstallService;

    public SlackTokensRevokedMessageHandler(
            SlackUninstallService uninstallService, EventPayloadDeserializer deserializer) {
        super("tokens_revoked", deserializer);
        this.uninstallService = uninstallService;
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        JsonNode botTokens = root.path("event").path("tokens").path("bot");
        if (!botTokens.isArray() || botTokens.isEmpty()) {
            log.info(
                    "Slack tokens_revoked for team {} revoked no bot token — skipping workspace teardown",
                    teamId(root));
            return;
        }
        uninstallService.onUninstall(
                teamId(root), "tokens_revoked", root.path("event_id").asString(""));
    }
}
