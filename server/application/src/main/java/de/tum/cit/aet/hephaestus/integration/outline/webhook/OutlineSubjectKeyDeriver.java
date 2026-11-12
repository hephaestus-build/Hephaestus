package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds NATS subjects + dedup keys for Outline webhook deliveries.
 *
 * <p>Subject format: {@code outline.<subscriptionId>.<event>}. The subscription id is a UUID (no
 * dots) and scopes the delivery to a single workspace's registered subscription; the event
 * ({@code documents.update}, …) has its dots replaced by {@code ~} so it never collides with the
 * NATS token boundary ({@code documents~update}). Neither token is case-folded: the subscription id
 * is passed through byte-for-byte so the derived subject matches the consumer's subscription filter
 * ({@link de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath#subscriptionFilter}),
 * mirroring the Slack sibling deriver.
 *
 * <p>Dedup key: {@code "outline-" + <delivery id>} — the top-level {@code id} of the delivery
 * envelope, a UUID unique per event that Outline reuses across retries of the same delivery.
 * (Never key on {@code payload.id}: two consecutive edits of one document are distinct events
 * with the same document id, and a document-keyed hash would silently swallow the second one
 * inside the JetStream dedup window.) Fallback when the body is unparsable or carries no id:
 * SHA-256 of the raw body (distinct events differ at least in {@code createdAt}). The
 * {@code "outline-"} vendor prefix guarantees cross-vendor uniqueness on the shared window.
 *
 * <p>Webhook-role only: subjects are derived on ingest. The consumer side parses them back with
 * {@link OutlineSubjectParser}, which stays ungated because the server role needs it.
 */
@Component
@ConditionalOnWebhookRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineSubjectKeyDeriver implements SubjectKeyDeriver {

    static final String SUBJECT_PREFIX = "outline.";
    static final String UNKNOWN_TOKEN = "unknown";

    private final ObjectMapper objectMapper;

    public OutlineSubjectKeyDeriver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.OUTLINE;
    }

    @Override
    public String deriveSubject(JsonNode payload, Map<String, String> headers) {
        String subscriptionId = sanitize(textOrEmpty(payload, "webhookSubscriptionId"));
        if (subscriptionId.isEmpty()) {
            subscriptionId = UNKNOWN_TOKEN;
        }
        String event = sanitize(textOrEmpty(payload, "event"));
        if (event.isEmpty()) {
            event = UNKNOWN_TOKEN;
        }
        return SUBJECT_PREFIX + subscriptionId + "." + event;
    }

    @Override
    public String deriveDedupKey(byte[] body, Map<String, String> headers) {
        JsonNode payload = tryParse(body);
        if (payload != null) {
            String deliveryId = textOrEmpty(payload, "id");
            if (!deliveryId.isBlank()) {
                return "outline-" + deliveryId;
            }
        }
        String hex = HexFormat.of().formatHex(sha256(body == null ? new byte[0] : body));
        return "outline-" + hex.substring(0, Math.min(32, hex.length()));
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace('.', '~');
    }

    private @Nullable JsonNode tryParse(byte @Nullable [] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString("");
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
