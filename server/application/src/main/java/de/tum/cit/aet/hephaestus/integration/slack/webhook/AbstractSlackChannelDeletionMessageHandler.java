package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Reads the original provider timestamp for public and private channel deletions. */
abstract class AbstractSlackChannelDeletionMessageHandler extends SlackChannelLifecycleMessageHandler {

    AbstractSlackChannelDeletionMessageHandler(
            String eventType, SlackChannelLifecycleService lifecycleService, EventPayloadDeserializer deserializer) {
        super(eventType, lifecycleService, deserializer);
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        JsonNode event = root.path("event");
        Instant erasedAt = SlackTs.toInstant(event.path("event_ts").asString(""));
        if (erasedAt == null) {
            long seconds = root.path("event_time").asLong(0);
            if (seconds <= 0 || seconds > Instant.MAX.getEpochSecond())
                throw new PayloadParsingException("Slack channel deletion has no valid event time");
            erasedAt = Instant.ofEpochSecond(seconds);
        }
        lifecycleService.onDeleted(teamId(root), event, erasedAt);
    }
}
