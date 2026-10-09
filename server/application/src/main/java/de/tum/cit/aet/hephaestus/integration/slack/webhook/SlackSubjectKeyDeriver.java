package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slack adapter for {@link SubjectKeyDeriver}. The unified {@code /webhooks/slack}
 * receiver publishes every verified Slack Events API callback to a flat Slack stream:
 * {@code slack.<team>.<scope>.<event>}.
 */
@Component
public class SlackSubjectKeyDeriver implements SubjectKeyDeriver {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PLACEHOLDER = "?";
    private static final String SUBJECT_PREFIX = "slack.";

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    @Override
    public String deriveSubject(JsonNode payload, Map<String, String> headers) {
        return subjectFor(payload);
    }

    @Override
    public String deriveDedupKey(byte[] body, Map<String, String> headers) {
        try {
            return dedupIdFor(MAPPER.readTree(body));
        } catch (Exception e) {
            return "slack-" + sha256TruncatedHex(body);
        }
    }

    /** Build a NATS subject from a Slack Events API envelope. */
    public String subjectFor(JsonNode root) {
        JsonNode event = root == null ? null : root.path("event");
        String team = sanitize(teamId(root));
        String eventType = eventType(event);
        String scope = scopeFor(event, eventType);
        return SUBJECT_PREFIX + team + "." + sanitize(scope) + "." + sanitize(eventType);
    }

    /** Build the JetStream dedup id. Slack's {@code event_id} is stable across retries. */
    public String dedupIdFor(JsonNode root) {
        String eventId = root == null ? "" : root.path("event_id").asString("");
        if (!eventId.isBlank()) {
            return "slack-" + eventId;
        }
        String team = teamId(root);
        JsonNode event = root == null ? null : root.path("event");
        String composite = team + "|"
                + eventType(event)
                + "|"
                + textAt(event, "event_ts")
                + "|"
                + textAt(event, "ts")
                + "|"
                + textAt(event, "deleted_ts")
                + "|"
                + textAt(event, "channel")
                + "|"
                + textAt(event, "user")
                + "|"
                + textAt(event, "subtype")
                + "|"
                + textAt(event == null ? null : event.path("previous_message"), "ts")
                + "|"
                + textAt(event == null ? null : event.path("message"), "ts");
        return "slack-" + sha256TruncatedHex(composite.getBytes(StandardCharsets.UTF_8));
    }

    private static String textAt(@Nullable JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.path(field).asString("");
    }

    private static String teamId(@Nullable JsonNode root) {
        if (root == null) {
            return "";
        }
        String team = root.path("team_id").asString("");
        if (!team.isBlank()) {
            return team;
        }
        JsonNode firstAuthorization = root.path("authorizations").path(0);
        return firstAuthorization.path("team_id").asString("");
    }

    private static String eventType(@Nullable JsonNode event) {
        if (event == null || event.isMissingNode() || event.isNull()) {
            return "unknown";
        }
        String type = event.path("type").asString("");
        if ("message".equals(type)) {
            String channelType = event.path("channel_type").asString("");
            if ("im".equals(channelType)) {
                return "message_im";
            }
            if ("channel".equals(channelType) || "group".equals(channelType)) {
                return "message";
            }
            return "message_" + (channelType.isBlank() ? "unknown" : channelType);
        }
        return type.isBlank() ? "unknown" : type;
    }

    private static String scopeFor(@Nullable JsonNode event, String eventType) {
        if (event == null || event.isMissingNode() || event.isNull()) {
            return "workspace";
        }
        return switch (eventType) {
            case "message" -> event.path("channel").asString("");
            case "message_im", "app_home_opened" ->
                firstNonBlank(
                        event.path("user").asString(""), event.path("channel").asString(""));
            case "member_joined_channel" -> event.path("channel").asString("");
            case "app_uninstalled", "tokens_revoked" -> "workspace";
            default ->
                firstNonBlank(
                        event.path("channel").asString(""), event.path("user").asString(""), "workspace");
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) return PLACEHOLDER;
        return value.replace('.', '~');
    }

    private static String sha256TruncatedHex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (data != null) md.update(data);
            String hex = HexFormat.of().formatHex(md.digest());
            return hex.length() <= 32 ? hex : hex.substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
