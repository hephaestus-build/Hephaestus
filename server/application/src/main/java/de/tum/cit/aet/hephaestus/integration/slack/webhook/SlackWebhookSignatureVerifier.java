package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Slack Events API verifier for the unified {@code POST /webhooks/slack} ingress path. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackWebhookSignatureVerifier implements WebhookSignatureVerifier {

    private static final String HEADER_TIMESTAMP = "X-Slack-Request-Timestamp";
    private static final String HEADER_SIGNATURE = "X-Slack-Signature";

    private final SlackSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    public SlackWebhookSignatureVerifier(SlackSignatureVerifier signatureVerifier, ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    @Override
    public VerificationResult verify(WebhookRequest request) {
        SlackSignatureVerifier.Verification verification = signatureVerifier.check(
                header(request.headers(), HEADER_TIMESTAMP),
                header(request.headers(), HEADER_SIGNATURE),
                request.body(),
                Instant.now().getEpochSecond());
        return switch (verification.status()) {
            case VALID -> verifiedEnvelope(request.body());
            case MISSING_SIGNATURE -> new VerificationResult.MissingSignature();
            case STALE_TIMESTAMP -> new VerificationResult.StaleTimestamp(verification.driftSeconds());
            case INVALID -> new VerificationResult.Invalid("slack-signature-mismatch");
        };
    }

    private VerificationResult verifiedEnvelope(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            return new VerificationResult.Invalid("invalid-json");
        }
        if ("url_verification".equals(root.path("type").asString(""))) {
            return new VerificationResult.RespondImmediately(
                    200,
                    "text/plain; charset=utf-8",
                    root.path("challenge").asString("").getBytes(StandardCharsets.UTF_8));
        }
        return new VerificationResult.Verified();
    }

    private static @Nullable String header(@Nullable Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
