package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The Outline verifier: a correct HMAC over {@code "<millis>." + body} keyed by the route's secret
 * verifies; a wrong signature, a replayed (stale-timestamp) delivery and a missing header each map to
 * the right {@link VerificationResult}. {@code t} is
 * epoch <em>milliseconds</em> against a seconds-based ±300s window.
 */
class OutlineWebhookSignatureVerifierTest extends BaseUnitTest {

    private static final byte[] SECRET = "signing-secret".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final OutlineWebhookSignatureVerifier VERIFIER = new OutlineWebhookSignatureVerifier(CLOCK);

    private static String sign(long timestampMillis, byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestampMillis + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static WebhookRequest request(@Nullable String signatureHeader, byte[] body) {
        Map<String, String> headers = signatureHeader == null ? Map.of() : Map.of("Outline-Signature", signatureHeader);
        return new WebhookRequest(body, headers);
    }

    @Test
    void verify_acceptsAWellFormedSignature() {
        byte[] body = "{\"event\":\"documents.update\"}".getBytes(StandardCharsets.UTF_8);
        long ts = NOW.toEpochMilli();
        String header = "t=" + ts + ",s=" + sign(ts, body, SECRET);

        assertThat(VERIFIER.verify(request(header, body), SECRET)).isInstanceOf(VerificationResult.Verified.class);
    }

    @Test
    void verify_rejectsATamperedBody() {
        byte[] signedBody = "{\"event\":\"documents.update\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "{\"event\":\"documents.delete\"}".getBytes(StandardCharsets.UTF_8);
        long ts = NOW.toEpochMilli();
        String header = "t=" + ts + ",s=" + sign(ts, signedBody, SECRET);

        assertThat(VERIFIER.verify(request(header, tamperedBody), SECRET))
                .isInstanceOf(VerificationResult.Invalid.class);
    }

    @Test
    void verify_rejectsAReplayedStaleTimestamp() {
        byte[] body = "{\"event\":\"documents.update\"}".getBytes(StandardCharsets.UTF_8);
        long staleTs = NOW.minusSeconds(301).toEpochMilli();
        String header = "t=" + staleTs + ",s=" + sign(staleTs, body, SECRET);

        VerificationResult result = VERIFIER.verify(request(header, body), SECRET);
        assertThat(result).isInstanceOf(VerificationResult.StaleTimestamp.class);
        assertThat(((VerificationResult.StaleTimestamp) result).driftSeconds()).isEqualTo(301);
    }

    @Test
    void verify_missingHeaderIsMissingSignature() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(VERIFIER.verify(request(null, body), SECRET))
                .isInstanceOf(VerificationResult.MissingSignature.class);
    }

    @Test
    void verify_nonNumericTimestampIsMalformed() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String header = "t=notanumber,s=" + sign(NOW.toEpochMilli(), body, SECRET);

        VerificationResult result = VERIFIER.verify(request(header, body), SECRET);
        assertThat(result).isInstanceOf(VerificationResult.Invalid.class);
        assertThat(((VerificationResult.Invalid) result).reason()).isEqualTo("malformed-timestamp");
    }

    @Test
    void verify_nonHexSignatureIsMalformed() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String header = "t=" + NOW.toEpochMilli() + ",s=zz"; // 'z' is not a hex digit

        VerificationResult result = VERIFIER.verify(request(header, body), SECRET);
        assertThat(result).isInstanceOf(VerificationResult.Invalid.class);
        assertThat(((VerificationResult.Invalid) result).reason()).isEqualTo("malformed-signature-hex");
    }
}
