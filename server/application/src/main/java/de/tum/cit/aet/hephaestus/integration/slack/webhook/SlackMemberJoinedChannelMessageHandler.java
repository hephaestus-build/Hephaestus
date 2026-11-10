package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelJoinNoticeHandler;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Posts just-in-time consent notices for new channel members. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackMemberJoinedChannelMessageHandler extends AbstractSlackEnvelopeHandler {

    private static final Duration MAX_NOTICE_EVENT_AGE = Duration.ofMinutes(10);

    private final SlackChannelJoinNoticeHandler joinNoticeHandler;
    private final Clock clock;

    public SlackMemberJoinedChannelMessageHandler(
            SlackChannelJoinNoticeHandler joinNoticeHandler, EventPayloadDeserializer deserializer) {
        super("member_joined_channel", deserializer);
        this.joinNoticeHandler = joinNoticeHandler;
        this.clock = Clock.systemUTC();
    }

    @Override
    protected void handleEnvelope(JsonNode root) {
        String teamId = teamId(root);
        JsonNode event = root.path("event");
        // Staleness only suppresses the time-sensitive ephemeral notice. The bot-self-join registration (a durable
        // consent-surface write) must still apply on a late redelivery, or a poison-backoff replay would silently
        // lose the channel's PENDING allow-list row.
        joinNoticeHandler.onMemberJoined(teamId, event, !isStaleReplay(root));
    }

    private boolean isStaleReplay(JsonNode root) {
        long eventTimeSeconds = root.path("event_time").asLong(0L);
        if (eventTimeSeconds <= 0) {
            return false;
        }
        long ageSeconds = clock.instant().getEpochSecond() - eventTimeSeconds;
        return ageSeconds > MAX_NOTICE_EVENT_AGE.toSeconds();
    }
}
