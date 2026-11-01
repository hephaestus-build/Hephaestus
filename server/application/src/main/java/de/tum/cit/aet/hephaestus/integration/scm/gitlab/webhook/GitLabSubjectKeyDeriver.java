package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Builds the NATS subject and dedup key of a delivery that {@link GitLabConnectionWebhookController} authenticated for
 * one connection.
 *
 * <p>Subject format: {@code gitlab.?connection.<connectionId>.<event>}. The {@code event} token is the payload's
 * {@code object_kind}, or its {@code event_name} folded onto a handler key; lowercased via {@link Locale#ROOT}, with
 * dots replaced by {@code ~}. The payload's project path never selects a subject: the connection the route credential
 * names does.
 */
@Component
public class GitLabSubjectKeyDeriver {

    static final String SUBJECT_PREFIX = "gitlab.";
    static final String UNKNOWN_TOKEN = "unknown";

    /** Namespace token of a connection subject. */
    public static final String CONNECTION_TOKEN = "?connection";

    private static final String HEADER_EVENT_UUID = "x-gitlab-event-uuid";

    /** The subject a delivery authenticated for {@code connectionId} is published on, whatever its payload path. */
    public String deriveConnectionSubject(long connectionId, JsonNode payload) {
        return SUBJECT_PREFIX + CONNECTION_TOKEN + "." + connectionId + "." + eventToken(payload);
    }

    private static String eventToken(JsonNode payload) {
        String rawEvent = textOrEmpty(payload, "object_kind");
        if (rawEvent.isEmpty()) {
            rawEvent = textOrEmpty(payload, "event_name");
        }
        String event = normalizeEvent(rawEvent);
        return event.isEmpty() ? UNKNOWN_TOKEN : event;
    }

    /**
     * The dedup key of a delivery authenticated for {@code connectionId}. Every hook of one connection — an old and a new
     * key generation during a rotation, say — sends the same event with the same event UUID and body but its own
     * delivery id, so the key ignores the delivery id and the generation; two connections each keep their copy, and
     * recursive events that share an event UUID keep theirs through the body.
     */
    public String deriveConnectionDedupKey(long connectionId, byte[] body, Map<String, String> headers) {
        Map<String, String> lower = lowercaseKeys(headers);
        String marker =
                lower.getOrDefault(HEADER_EVENT_UUID, "").trim() + "|" + lower.getOrDefault("x-gitlab-event", "");
        String hex = HexFormat.of().formatHex(sha256(body, marker.getBytes(StandardCharsets.UTF_8)));
        return "gitlab-c" + connectionId + "-" + hex.substring(0, 32);
    }

    private static byte[] sha256(byte[] body, byte[] suffix) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(body);
            md.update((byte) '|');
            md.update(suffix);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JRE; if it's missing, fail loudly.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sanitizeEvent(String value) {
        if (value == null || value.isEmpty()) return "";
        // Lowercase + replace dots so the event component never collides with NATS
        // token boundaries.
        return value.toLowerCase(Locale.ROOT).replace('.', '~');
    }

    /**
     * Normalizes the raw discriminator into the wire event token. {@code object_kind} values
     * ({@code merge_request}, {@code issue}, {@code note}, …) pass through sanitized. GitLab's
     * group-tier events instead arrive via {@code event_name} with a granular verb —
     * {@code project_create}, {@code subgroup_destroy}, {@code user_add_to_group} — which we fold
     * onto the stable keys the handlers register under ({@link GitLabEventType#PROJECT},
     * {@link GitLabEventType#SUBGROUP}, {@link GitLabEventType#MEMBER}). This is the single point
     * of normalization the handler javadocs already promise; registering the handlers under the raw
     * verbs instead would scatter 3–5 synonyms per handler and still leave the subject token
     * unnormalized (so a dispatcher round-trip could not agree on one key).
     */
    private static String normalizeEvent(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("project_")) {
            return GitLabEventType.PROJECT.getValue();
        }
        if (lower.startsWith("subgroup_")) {
            return GitLabEventType.SUBGROUP.getValue();
        }
        // user_add_to_group / user_remove_from_group / user_update_for_group → "member".
        // Project-member verbs (user_*_to_team) end with "_team", so they never fold to "member".
        if (lower.startsWith("user_") && lower.endsWith("_group")) {
            return GitLabEventType.MEMBER.getValue();
        }
        return sanitizeEvent(raw);
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString("");
    }

    private static Map<String, String> lowercaseKeys(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return out;
    }
}
