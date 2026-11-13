package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Drops a Slack channel message before it is stored unless the channel's consent is {@code ACTIVE} in a workspace
 * connected to its Slack team. A failed consent read fails the delivery, so nothing is stored and Slack retries it. The
 * server checks consent again for each workspace when it stores the message.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackChannelMessagePublishGate implements WebhookPublishGate {

    private static final Pattern SLACK_ID = Pattern.compile("[A-Z0-9]{1,32}");

    private final ConnectionService connections;
    private final SlackMonitoredChannelRepository channels;
    private final boolean conversationIngestEnabled;

    public SlackChannelMessagePublishGate(
            ConnectionService connections,
            SlackMonitoredChannelRepository channels,
            @Value("${hephaestus.integration.slack.conversation-ingest.enabled:true}")
                    boolean conversationIngestEnabled) {
        this.connections = connections;
        this.channels = channels;
        this.conversationIngestEnabled = conversationIngestEnabled;
    }

    /** Whether {@code value} has the shape of a Slack team or channel id, and is therefore a safe key token. */
    public static boolean isSlackId(String value) {
        return SLACK_ID.matcher(value).matches();
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    @Override
    public Decision evaluate(JsonNode payload, Map<String, String> headers) {
        JsonNode event = payload.path("event");
        if (!isChannelMessage(event)) {
            return Decision.allow();
        }
        if (!conversationIngestEnabled) {
            return Decision.drop("slack-channel-ingest-disabled");
        }
        // A delete must reach the tombstone in EVERY consent state: a message stored while the channel was ACTIVE
        // still exists during PAUSED, and the author's deletion has to erase our copy too (GDPR parity). The
        // tombstone is contentless, so letting it through never stores new content.
        if ("message_deleted".equals(event.path("subtype").asString(""))) {
            return Decision.allow();
        }
        String teamId = teamId(payload);
        String channelId = event.path("channel").asString("");
        if (teamId.isBlank() || channelId.isBlank()) {
            return Decision.drop("slack-channel-message-missing-team-or-channel");
        }
        if (!isSlackId(teamId) || !isSlackId(channelId)) {
            return Decision.drop("slack-channel-message-malformed-id");
        }
        return isActive(teamId, channelId) ? Decision.allow() : Decision.drop("slack-channel-not-active");
    }

    /** Throws when consent cannot be read, so nothing is stored. */
    private boolean isActive(String teamId, String channelId) {
        return connections.findWorkspaceIdsWithConnection(IntegrationKind.SLACK, IntegrationState.ACTIVE).stream()
                .filter(workspaceId -> connections
                        .findSlackConfig(workspaceId)
                        .map(ConnectionConfig.SlackConfig::teamId)
                        .filter(teamId::equals)
                        .isPresent())
                .anyMatch(workspaceId -> channels.findConsentState(workspaceId, channelId)
                        .filter(ConsentState.ACTIVE::equals)
                        .isPresent());
    }

    private static boolean isChannelMessage(JsonNode event) {
        if (!"message".equals(event.path("type").asString(""))) {
            return false;
        }
        String channelType = event.path("channel_type").asString("");
        return "channel".equals(channelType) || "group".equals(channelType);
    }

    private static String teamId(JsonNode root) {
        String team = root.path("team_id").asString("");
        if (!team.isBlank()) {
            return team;
        }
        return root.path("authorizations").path(0).path("team_id").asString("");
    }
}
