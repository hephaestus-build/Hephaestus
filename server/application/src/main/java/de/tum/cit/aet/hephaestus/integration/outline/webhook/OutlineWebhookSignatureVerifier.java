package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource.SecretLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SPI-shaped verifier for inbound Outline change-notification deliveries on the unified
 * {@code /webhooks/outline} JetStream lane. Outline signs each delivery with
 * {@code HMAC_SHA256(secret, "<timestamp>." + rawBody)} and sends the result in the
 * {@code Outline-Signature: t=<ts>,s=<hex>} header, where {@code t} is epoch <em>milliseconds</em>.
 *
 * <p>The signing secret is scoped per subscription: the subscription id arrives in the event body as
 * an untrusted routing key that selects the stored secret ({@link OutlineWebhookSecretSource}), so a
 * forged id selects a secret the attacker does not hold and the HMAC fails. This class resolves that
 * secret via the OUTLINE-filtered {@link WebhookSecretSource} (the {@code GitLabWebhookSignatureVerifier}
 * ctor pattern), recomputes the digest over the exact request bytes, compares constant-time, and enforces
 * a ±{@value #MAX_SKEW_SECONDS}s replay window on the timestamp.
 *
 * <p>Webhook-role only, matching {@code SlackWebhookSignatureVerifier}. The bootstrap's
 * {@code WebhookSignatureVerifier}/{@code SubjectKeyDeriver} requirement is itself gated on the
 * webhook role, so a non-webhook role boots cleanly without these beans.
 */
@Component
@ConditionalOnWebhookRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineWebhookSignatureVerifier implements WebhookSignatureVerifier {

    static final String HEADER_SIGNATURE = "outline-signature";
    private static final String HMAC_ALG = "HmacSHA256";

    /** Outline's replay window; {@code t} is millis, the window is seconds. */
    static final long MAX_SKEW_SECONDS = 300;

    private final WebhookSecretSource secretSource;
    private final Clock clock;

    @Autowired
    public OutlineWebhookSignatureVerifier(List<WebhookSecretSource> secretSources) {
        this(pickOutlineSource(secretSources), Clock.systemUTC());
    }

    /** Test-friendly constructor — direct injection of the source + clock. */
    OutlineWebhookSignatureVerifier(WebhookSecretSource secretSource, Clock clock) {
        this.secretSource = secretSource;
        this.clock = clock;
    }

    private static WebhookSecretSource pickOutlineSource(List<WebhookSecretSource> sources) {
        return sources.stream()
                .filter(s -> s.kind() == IntegrationKind.OUTLINE)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No WebhookSecretSource bean registered for OUTLINE"));
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.OUTLINE;
    }

    @Override
    public VerificationResult verify(WebhookRequest request) {
        Map<String, String> headers = normalizeHeaders(request.headers());
        Optional<ParsedSignature> parsed = parse(headers.get(HEADER_SIGNATURE));
        if (parsed.isEmpty()) {
            return new VerificationResult.MissingSignature();
        }
        ParsedSignature signature = parsed.get();

        final long tsMillis;
        try {
            tsMillis = Long.parseLong(signature.timestamp());
        } catch (NumberFormatException e) {
            return new VerificationResult.Invalid("malformed-timestamp");
        }
        long drift = Math.abs(clock.instant().getEpochSecond() - tsMillis / 1000L);
        if (drift > MAX_SKEW_SECONDS) {
            return new VerificationResult.StaleTimestamp(drift);
        }

        final byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature.signatureHex());
        } catch (IllegalArgumentException e) {
            return new VerificationResult.Invalid("malformed-signature-hex");
        }

        Optional<byte[]> secret = secretSource.getSecret(new SecretLookup(headers, request.body()));
        if (secret.isEmpty()) {
            return new VerificationResult.Invalid("unresolved-secret");
        }

        byte[] expected = computeHmac(secret.get(), signature.timestamp(), request.body());
        if (expected == null) {
            return new VerificationResult.Invalid("hmac-init-failed");
        }
        if (MessageDigest.isEqual(expected, provided)) {
            return new VerificationResult.Verified();
        }
        return new VerificationResult.Invalid("signature-mismatch");
    }

    private static byte @Nullable [] computeHmac(byte[] secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** The {@code t} (timestamp) / {@code s} (signature) pair parsed out of the header. */
    record ParsedSignature(String timestamp, String signatureHex) {}

    /**
     * Parses {@code Outline-Signature: t=<ts>,s=<hex>} (order- and whitespace-tolerant). Empty when
     * the header is missing or does not carry both fields.
     */
    static Optional<ParsedSignature> parse(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("t".equals(key)) {
                timestamp = value;
            } else if ("s".equals(key)) {
                signature = value;
            }
        }
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedSignature(timestamp, signature));
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return out;
    }
}
