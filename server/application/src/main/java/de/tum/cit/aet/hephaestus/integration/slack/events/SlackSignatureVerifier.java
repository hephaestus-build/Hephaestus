package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Verifies Slack's v0 request signatures with the app signing secret. */
@Component
@ConditionalOnWebhookRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackSignatureVerifier {

    private static final long MAX_SKEW_SECONDS = 300;
    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] signingSecret;

    public SlackSignatureVerifier(@Value("${hephaestus.integration.slack.signing-secret:}") String signingSecret) {
        if (signingSecret.isBlank()) {
            throw new IllegalStateException(
                    "Slack integration is enabled but hephaestus.integration.slack.signing-secret is blank");
        }
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    public Verification check(
            @Nullable String timestamp, @Nullable String signature, byte @Nullable [] rawBody, long nowEpochSeconds) {
        if (timestamp == null || signature == null || rawBody == null) {
            return Verification.missingSignature();
        }
        final long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return Verification.invalid();
        }
        long driftSeconds = Math.abs(nowEpochSeconds - ts);
        if (driftSeconds > MAX_SKEW_SECONDS) {
            return Verification.staleTimestamp(driftSeconds);
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(signingSecret, HMAC_ALG));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            byte[] digest = mac.doFinal(rawBody);
            String expected = "v0=" + HexFormat.of().formatHex(digest);
            boolean valid = MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
            return valid ? Verification.valid() : Verification.invalid();
        } catch (Exception e) {
            return Verification.invalid();
        }
    }

    public boolean verify(
            @Nullable String timestamp, @Nullable String signature, byte @Nullable [] rawBody, long nowEpochSeconds) {
        return check(timestamp, signature, rawBody, nowEpochSeconds).status() == Verification.Status.VALID;
    }

    public record Verification(Status status, long driftSeconds) {
        public enum Status {
            VALID,
            MISSING_SIGNATURE,
            STALE_TIMESTAMP,
            INVALID,
        }

        static Verification valid() {
            return new Verification(Status.VALID, 0);
        }

        static Verification missingSignature() {
            return new Verification(Status.MISSING_SIGNATURE, 0);
        }

        static Verification staleTimestamp(long driftSeconds) {
            return new Verification(Status.STALE_TIMESTAMP, driftSeconds);
        }

        static Verification invalid() {
            return new Verification(Status.INVALID, 0);
        }
    }
}
