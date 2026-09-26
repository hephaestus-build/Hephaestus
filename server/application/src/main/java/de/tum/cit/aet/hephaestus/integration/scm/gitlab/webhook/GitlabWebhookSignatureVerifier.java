package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource.SecretLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Dual-mode GitLab webhook signature verifier.
 *
 * <p>Two coexisting wire formats:
 * <ul>
 *   <li><b>Legacy plaintext</b> — {@code X-Gitlab-Token} header byte-equals the
 *       shared secret. GitLab's only mode before 19.0; {@code GitLabWebhookService}
 *       registers it as a hook's {@code token} for a {@code PLAINTEXT} connection.
 *   <li><b>Standard Webhooks HMAC (GitLab 19.0+)</b> — {@code webhook-signature}
 *       carries one or more space-separated {@code v1,<base64-hmac>} entries. The
 *       signing secret has the form {@code whsec_<base64>} ({@link #signingKey}); we strip
 *       the prefix, base64-decode the rest to get the 32-byte MAC key, then compute
 *       {@code HMAC_SHA256(key, "<webhook-id>.<webhook-timestamp>.<body>")}.
 *       Replay protection: {@code webhook-timestamp} must be within
 *       {@link #TIMESTAMP_TOLERANCE} of now.
 * </ul>
 *
 * <p>GitLab sends {@code X-Gitlab-Token} alongside the signature when a hook has both
 * tokens configured. Whenever {@code webhook-signature} is present — even blank or
 * malformed — only the HMAC decides: an attacker who only knows the legacy shared
 * secret cannot forge an HMAC over an arbitrary body.
 *
 * <p>References: <a href="https://docs.gitlab.com/user/project/integrations/webhooks/#signing-tokens">GitLab
 * signing tokens</a>, <a href="https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md">Standard
 * Webhooks spec</a>.
 */
@Component
public class GitlabWebhookSignatureVerifier implements WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(GitlabWebhookSignatureVerifier.class);

    static final String HEADER_TOKEN = "x-gitlab-token";
    static final String HEADER_SIGNATURE = "webhook-signature";
    static final String HEADER_WEBHOOK_ID = "webhook-id";
    static final String HEADER_WEBHOOK_TIMESTAMP = "webhook-timestamp";

    static final String SIGNATURE_V1_PREFIX = "v1,";
    static final String WHSEC_PREFIX = "whsec_";
    static final String HMAC_SHA256 = "HmacSHA256";
    static final int SIGNING_KEY_BYTES = 32;

    /**
     * Our replay window, applied in both directions. Standard Webhooks asks only for a
     * "reasonable tolerance"; five minutes is Hephaestus's choice, shared with Slack's
     * verifier ({@code WebhookProperties.Stream.REPLAY_TOLERANCE_FLOOR} depends on it).
     */
    static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);

    private final WebhookSecretSource secretSource;
    private final Clock clock;

    @Autowired
    public GitlabWebhookSignatureVerifier(List<WebhookSecretSource> secretSources) {
        this(pickGitlabSource(secretSources), Clock.systemUTC());
    }

    /** Test-friendly constructor — direct injection of the source + clock. */
    GitlabWebhookSignatureVerifier(WebhookSecretSource secretSource, Clock clock) {
        this.secretSource = secretSource;
        this.clock = clock;
    }

    private static WebhookSecretSource pickGitlabSource(List<WebhookSecretSource> sources) {
        return sources.stream()
                .filter(s -> s.kind() == IntegrationKind.GITLAB)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No WebhookSecretSource bean registered for GITLAB"));
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public VerificationResult verify(WebhookRequest request) {
        Map<String, String> normalized = normalizeHeaders(request.headers());
        String signatureHeader = normalized.get(HEADER_SIGNATURE);
        String tokenHeader = normalized.get(HEADER_TOKEN);

        // Presence alone selects the HMAC path: falling back to the weaker plaintext token
        // on a blank, malformed or mismatched signature would be a downgrade primitive.
        if (signatureHeader != null) {
            return verifyWhsec(request, normalized, signatureHeader);
        }
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            return verifyPlaintext(request, normalized, tokenHeader);
        }
        return new VerificationResult.MissingSignature();
    }

    private VerificationResult verifyPlaintext(
            WebhookRequest request, Map<String, String> headers, String tokenHeader) {
        Optional<byte[]> secret = secretSource.getSecret(new SecretLookup(headers));
        if (secret.isEmpty()) {
            log.warn("GitLab plaintext verifier: no shared secret available");
            return new VerificationResult.Invalid("missing-secret");
        }
        // Hash both sides to fixed-width 32-byte digests before the constant-time compare.
        // A raw MessageDigest.isEqual over the byte arrays loops over the presented token's
        // length, so timing would leak the attacker-controlled token length (and a length
        // mismatch is trivially distinguishable) — narrowing brute-force. Hashing first makes
        // both inputs equal-length regardless of secret length, removing that side channel.
        byte[] tokenDigest = sha256(tokenHeader.getBytes(StandardCharsets.UTF_8));
        byte[] secretDigest = sha256(secret.get());
        if (tokenDigest == null || secretDigest == null) {
            return new VerificationResult.Invalid("hash-init-failed");
        }
        if (MessageDigest.isEqual(tokenDigest, secretDigest)) {
            return new VerificationResult.Verified();
        }
        return new VerificationResult.Invalid("token-mismatch");
    }

    private VerificationResult verifyWhsec(
            WebhookRequest request, Map<String, String> headers, String signatureHeader) {
        List<byte[]> presentedMacs = v1Macs(signatureHeader);
        if (presentedMacs.isEmpty()) {
            return new VerificationResult.Invalid("malformed-signature");
        }
        String msgId = headers.get(HEADER_WEBHOOK_ID);
        String timestampHeader = headers.get(HEADER_WEBHOOK_TIMESTAMP);
        if (msgId == null || msgId.isBlank()) {
            return new VerificationResult.Invalid("missing-webhook-id");
        }
        if (timestampHeader == null || timestampHeader.isBlank()) {
            return new VerificationResult.Invalid("missing-webhook-timestamp");
        }

        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return new VerificationResult.Invalid("malformed-webhook-timestamp");
        }

        long nowSeconds = clock.instant().getEpochSecond();
        long drift = Math.abs(nowSeconds - timestampSeconds);
        if (drift > TIMESTAMP_TOLERANCE.toSeconds()) {
            return new VerificationResult.StaleTimestamp(drift);
        }

        Optional<byte[]> secret = secretSource.getSecret(new SecretLookup(headers));
        if (secret.isEmpty()) {
            log.warn("GitLab whsec verifier: no signing secret available");
            return new VerificationResult.Invalid("missing-secret");
        }

        byte[] hmacKey = signingKey(new String(secret.get(), StandardCharsets.UTF_8));
        if (hmacKey == null) {
            return new VerificationResult.Invalid("malformed-whsec-secret");
        }

        byte[] expectedMac = computeHmac(hmacKey, msgId, timestampHeader.trim(), request.body());
        if (expectedMac == null) {
            return new VerificationResult.Invalid("hmac-init-failed");
        }
        for (byte[] presentedMac : presentedMacs) {
            if (MessageDigest.isEqual(presentedMac, expectedMac)) {
                return new VerificationResult.Verified();
            }
        }
        return new VerificationResult.Invalid("signature-mismatch");
    }

    /** Decoded {@code v1} MACs; like the Standard Webhooks reference verifiers, skips any other entry. */
    private static List<byte[]> v1Macs(String signatureHeader) {
        List<byte[]> macs = new ArrayList<>();
        for (String entry : signatureHeader.split(" ")) {
            if (!entry.startsWith(SIGNATURE_V1_PREFIX)) continue;
            try {
                byte[] mac = Base64.getDecoder().decode(entry.substring(SIGNATURE_V1_PREFIX.length()));
                if (mac.length > 0) macs.add(mac);
            } catch (IllegalArgumentException e) {
                // Not base64: not a v1 signature.
            }
        }
        return macs;
    }

    /** SHA-256 digest, or {@code null} if the algorithm is somehow unavailable (never on a JRE). */
    private static byte @Nullable [] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * The HMAC key of a GitLab signing token, or {@code null} unless {@code secret} is
     * {@code whsec_} followed by the base64 of exactly {@value #SIGNING_KEY_BYTES} bytes — the
     * only form GitLab accepts as a hook's {@code signing_token}.
     */
    public static byte @Nullable [] signingKey(String secret) {
        if (!secret.startsWith(WHSEC_PREFIX)) {
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(secret.substring(WHSEC_PREFIX.length()));
            return key.length == SIGNING_KEY_BYTES ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte @Nullable [] computeHmac(byte[] key, String msgId, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            mac.update((msgId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return null;
        }
    }

    /**
     * Lowercase-keyed copy. HTTP headers are case-insensitive but {@link WebhookRequest}
     * gives us whatever map the controller built; normalize once at the top.
     */
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
