package de.tum.cit.aet.hephaestus.integration.scm.github.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource.SecretLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter for {@link WebhookSignatureVerifier}: validates
 * {@code X-Hub-Signature-256} HMAC-SHA256 against the raw body using the APP_GLOBAL
 * shared secret resolved by {@link GitHubWebhookSecretSource}. Constant-time compare
 * via {@link MessageDigest#isEqual(byte[], byte[])}; SHA-1 deliberately rejected.
 *
 * <p>Short-circuits the {@code ping} setup event with {@link
 * VerificationResult.RespondImmediately} — GitHub posts a ping immediately after the
 * App is installed to confirm reachability and treats any non-2xx as the App being
 * unreachable. The ping carries a valid signature in normal operation, but verifying
 * it here would still gate liveness on a configured secret which the operator may
 * not have wired yet during initial install. Returning {@code "pong"} acknowledges
 * the ping without leaking secret state.
 */
@Component
public class GitHubWebhookSignatureVerifier implements WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookSignatureVerifier.class);

    private static final String HEADER_EVENT = "X-GitHub-Event";
    private static final String HEADER_SIGNATURE_256 = "X-Hub-Signature-256";
    private static final String PING_EVENT = "ping";
    private static final String SHA256_PREFIX = "sha256=";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final byte[] PONG_BODY = "{\"status\":\"pong\"}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSecretSource secretSource;

    public GitHubWebhookSignatureVerifier(GitHubWebhookSecretSource secretSource) {
        this.secretSource = secretSource;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public VerificationResult verify(WebhookRequest request) {
        String eventType = headerCaseInsensitive(request, HEADER_EVENT);
        if (PING_EVENT.equalsIgnoreCase(eventType)) {
            // Setup-only liveness ping — no payload to publish; 200 OK with a pong body
            // is enough for GitHub to mark the App as reachable.
            return new VerificationResult.RespondImmediately(200, MediaType.APPLICATION_JSON_VALUE, PONG_BODY);
        }
        String signature = headerCaseInsensitive(request, HEADER_SIGNATURE_256);
        if (signature == null || signature.isBlank()) {
            return new VerificationResult.MissingSignature();
        }
        if (!signature.startsWith(SHA256_PREFIX)) {
            return new VerificationResult.Invalid("unsupported-signature-algo");
        }

        Optional<byte[]> secret = secretSource.getSecret(new SecretLookup(request.headers()));
        if (secret.isEmpty()) {
            log.warn("GitHub webhook rejected: shared secret not configured");
            return new VerificationResult.Invalid("missing-secret");
        }

        byte[] computed;
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.get(), HMAC_SHA256));
            computed = mac.doFinal(request.body());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.warn("GitHub webhook HMAC init failed: {}", e.toString());
            return new VerificationResult.Invalid("hmac-init-failed");
        }

        byte[] expected = (SHA256_PREFIX + HexFormat.of().formatHex(computed)).getBytes(StandardCharsets.UTF_8);
        byte[] header = signature.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(header, expected)) {
            return new VerificationResult.Verified();
        }
        return new VerificationResult.Invalid("signature-mismatch");
    }

    private static @Nullable String headerCaseInsensitive(WebhookRequest request, String name) {
        String direct = request.headers().get(name);
        if (direct != null) return direct;
        for (var entry : request.headers().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
