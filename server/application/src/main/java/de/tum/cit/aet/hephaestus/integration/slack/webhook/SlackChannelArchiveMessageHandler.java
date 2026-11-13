package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** A public channel was archived: {@code ACTIVE → PAUSED} (an archived channel receives no further messages). */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackChannelArchiveMessageHandler extends SlackChannelLifecycleMessageHandler {

    public SlackChannelArchiveMessageHandler(
            SlackChannelLifecycleService lifecycleService, EventPayloadDeserializer deserializer) {
        super("channel_archive", lifecycleService, deserializer);
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        lifecycleService.onArchived(teamId(root), root.path("event"));
    }
}
