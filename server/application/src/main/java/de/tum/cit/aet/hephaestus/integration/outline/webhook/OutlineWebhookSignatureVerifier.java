package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Verifies an inbound Outline change-notification delivery against the secret of the route it was posted to
 * ({@link OutlineRouteCredential}). Outline signs each delivery with
 * {@code HMAC_SHA256(secret, "<timestamp>." + rawBody)} and sends the result in the
 * {@code Outline-Signature: t=<ts>,s=<hex>} header, where {@code t} is epoch <em>milliseconds</em>. This class
 * recomputes the digest over the exact request bytes, compares constant-time, and enforces a
 * ±{@value #MAX_SKEW_SECONDS}s replay window on the timestamp.
 *
 * <p>Webhook-role only: {@link OutlineWebhookController} is its one caller.
 */
@Component
@ConditionalOnWebhookRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineWebhookSignatureVerifier {

    static final String HEADER_SIGNATURE = "outline-signature";
    private static final String HMAC_ALG = "HmacSHA256";

    /** Outline's replay window; {@code t} is millis, the window is seconds. */
    static final long MAX_SKEW_SECONDS = 300;

    private final Clock clock;

    public OutlineWebhookSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    /** Verifies a delivery posted to a route against that route's {@code secret}. */
    public VerificationResult verify(WebhookRequest request, byte[] secret) {
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

        byte[] expected = computeHmac(secret, signature.timestamp(), request.body());
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
        for (String part : header.split(",", -1)) {
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
