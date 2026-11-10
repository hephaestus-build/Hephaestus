package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * A channel was deleted on Slack's side: {@code * → REVOKED} — erase our copy of its raw + derived data too rather
 * than leave a zombie allow-list row and lingering thread-aggregate PII.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackChannelDeletedMessageHandler extends SlackChannelLifecycleMessageHandler {

    public SlackChannelDeletedMessageHandler(
            SlackChannelLifecycleService lifecycleService, EventPayloadDeserializer deserializer) {
        super("channel_deleted", lifecycleService, deserializer);
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        lifecycleService.onDeleted(teamId(root), root.path("event"));
    }
}
