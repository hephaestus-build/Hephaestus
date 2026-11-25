package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelLifecycleService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public SlackChannelDeletedMessageHandler(
            SlackChannelLifecycleService lifecycleService, EventPayloadDeserializer deserializer) {
        this("channel_deleted", lifecycleService, deserializer);
    }

    SlackChannelDeletedMessageHandler(
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
