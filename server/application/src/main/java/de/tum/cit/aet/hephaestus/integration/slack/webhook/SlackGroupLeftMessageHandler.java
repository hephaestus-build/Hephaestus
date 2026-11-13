package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The bot was removed from a private channel: {@code ACTIVE → PAUSED} (nothing is being read from it anymore). */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackGroupLeftMessageHandler extends SlackChannelLifecycleMessageHandler {

    public SlackGroupLeftMessageHandler(
            SlackChannelLifecycleService lifecycleService, EventPayloadDeserializer deserializer) {
        super("group_left", lifecycleService, deserializer);
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        lifecycleService.onBotRemoved(teamId(root), root.path("event"));
    }
}
