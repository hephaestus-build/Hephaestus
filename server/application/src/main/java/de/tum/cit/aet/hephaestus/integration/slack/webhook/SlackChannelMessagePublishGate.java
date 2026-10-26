package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Drops a Slack channel message before it is stored unless the channel's consent is {@code ACTIVE}. The receiver
 * reads consent from the NATS key-value bucket {@value #CONSENT_BUCKET}, which the server's
 * {@code SlackChannelConsentProjection} writes, so it needs no database. A missing key means no consent. A bucket that
 * is missing or unreadable fails the delivery, so nothing is published and Slack retries it.
 */
@Component
@ConditionalOnWebhookRole
@ConditionalOnProperty(
        name = {"hephaestus.integration.slack.enabled", "hephaestus.sync.nats.enabled"},
        havingValue = "true")
public class SlackChannelMessagePublishGate implements WebhookPublishGate {

    /** The key-value bucket of {@code ACTIVE} channels, keyed {@code <teamId>.<channelId>}. */
    public static final String CONSENT_BUCKET = "slack-channel-consent";

    private static final Pattern SLACK_ID = Pattern.compile("[A-Z0-9]{1,32}");

    private final Connection nats;
    private final boolean conversationIngestEnabled;
    private volatile @Nullable KeyValue consent;

    public SlackChannelMessagePublishGate(
            Connection nats,
            @Value("${hephaestus.integration.slack.conversation-ingest.enabled:true}")
                    boolean conversationIngestEnabled) {
        this.nats = nats;
        this.conversationIngestEnabled = conversationIngestEnabled;
    }

    /** Whether {@code value} has the shape of a Slack team or channel id, and is therefore a safe key token. */
    public static boolean isSlackId(String value) {
        return SLACK_ID.matcher(value).matches();
    }

    /** The bucket key of one channel. */
    public static String consentKey(String teamId, String channelId) {
        return teamId + "." + channelId;
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

    private boolean isActive(String teamId, String channelId) {
        try {
            KeyValue bucket = consent;
            if (bucket == null) {
                bucket = nats.keyValue(CONSENT_BUCKET);
                consent = bucket;
            }
            return bucket.get(consentKey(teamId, channelId)) != null;
        } catch (JetStreamApiException e) {
            throw new IllegalStateException("Slack consent lookup failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Slack consent lookup failed", e);
        }
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
