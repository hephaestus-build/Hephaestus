package de.tum.cit.aet.hephaestus.integration.scm.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests pinning the GitHub HMAC-SHA256 verifier to the canonical
 * {@code sha256=<hex>} layout the GitHub Apps platform sends. The HMAC routine itself
 * mirrors {@code integration.webhook.github.HmacVerifier} bit-for-bit; these tests
 * guard that the SPI-side adapter doesn't drift from it.
 */
class GithubWebhookSignatureVerifierTest extends BaseUnitTest {

    private static final String SECRET = "github-app-shared-secret-32-bytes-long-XYZ";

    @Test
    void validSignatureVerified() {
        byte[] body = body("{\"action\":\"opened\"}");
        String sig = "sha256=" + hmacHex(body);
        VerificationResult result = newVerifier(SECRET).verify(req(body, header("X-Hub-Signature-256", sig)));

        assertThat(result).isInstanceOf(VerificationResult.Verified.class);
    }

    @Test
    void tamperedBodyInvalid() {
        byte[] signedBody = body("{\"action\":\"opened\"}");
        String sig = "sha256=" + hmacHex(signedBody);
        byte[] tampered = body("{\"action\":\"OPENED\"}");

        VerificationResult result = newVerifier(SECRET).verify(req(tampered, header("X-Hub-Signature-256", sig)));

        assertThat(result).isInstanceOf(VerificationResult.Invalid.class);
        assertThat(((VerificationResult.Invalid) result).reason()).isEqualTo("signature-mismatch");
    }

    @Test
    void wrongAlgorithmPrefixInvalid() {
        // SHA-1 path (legacy X-Hub-Signature) must NOT be accepted via the new header name.
        byte[] body = body("{}");
        VerificationResult result =
                newVerifier(SECRET).verify(req(body, header("X-Hub-Signature-256", "sha1=deadbeef")));

        assertThat(result).isInstanceOf(VerificationResult.Invalid.class);
        assertThat(((VerificationResult.Invalid) result).reason()).isEqualTo("unsupported-signature-algo");
    }

    @Test
    void missingSignatureHeaderIsMissingSignature() {
        VerificationResult result = newVerifier(SECRET).verify(req(body("{}")));

        assertThat(result).isInstanceOf(VerificationResult.MissingSignature.class);
    }

    @Test
    void unconfiguredSecretSurfacesAsInvalid() {
        byte[] body = body("{}");
        String sig = "sha256=" + hmacHex(body);

        VerificationResult result = newVerifier(null).verify(req(body, header("X-Hub-Signature-256", sig)));

        assertThat(result).isInstanceOf(VerificationResult.Invalid.class);
        assertThat(((VerificationResult.Invalid) result).reason()).isEqualTo("missing-secret");
    }

    @Test
    void blankSignatureHeaderTreatedAsMissing() {
        VerificationResult result = newVerifier(SECRET).verify(req(body("{}"), header("X-Hub-Signature-256", "   ")));

        assertThat(result).isInstanceOf(VerificationResult.MissingSignature.class);
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        byte[] body = body("{}");
        String sig = "sha256=" + hmacHex(body);

        VerificationResult result = newVerifier(SECRET).verify(req(body, header("x-hub-signature-256", sig)));

        assertThat(result).isInstanceOf(VerificationResult.Verified.class);
    }

    @Test
    void verifierIdentifiesAsGithubKind() {
        assertThat(newVerifier(SECRET).kind()).isEqualTo(IntegrationKind.GITHUB);
    }

    @Test
    void pingEventShortCircuitsBeforeSignatureCheck() {
        // GitHub posts a setup-time ping with no signature; the verifier must respond
        // 200 OK before the HMAC step (so the App installation registration succeeds).
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-GitHub-Event", "ping");
        headers.put("X-GitHub-Delivery", "delivery-id");

        VerificationResult result = newVerifier(SECRET).verify(new WebhookRequest(body("{}"), headers));

        assertThat(result).isInstanceOf(VerificationResult.RespondImmediately.class);
        VerificationResult.RespondImmediately respond = (VerificationResult.RespondImmediately) result;
        assertThat(respond.statusCode()).isEqualTo(200);
        assertThat(new String(respond.body(), StandardCharsets.UTF_8)).contains("pong");
    }

    // Helpers

    private static GithubWebhookSignatureVerifier newVerifier(@Nullable String secret) {
        return new GithubWebhookSignatureVerifier(new GithubWebhookSecretSource(propsWithSecret(secret)));
    }

    /** Builds a fully-populated WebhookProperties (record requires all components). */
    private static WebhookProperties propsWithSecret(@Nullable String secret) {
        return new WebhookProperties(
                /* externalUrl */ null,
                secret,
                new WebhookProperties.TokenRotation(7, 90),
                new WebhookProperties.Publish(Duration.ofSeconds(9), 5, Duration.ofMillis(200)),
                WebhookPropertiesFixture.stream(),
                new WebhookProperties.Shutdown(Duration.ofSeconds(15)),
                new WebhookProperties.Http(26_214_400L),
                new WebhookProperties.Routing(null, null));
    }

    private static String hmacHex(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] body(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Map.Entry<String, String> header(String name, String value) {
        return Map.entry(name, value);
    }

    @SafeVarargs
    private static WebhookRequest req(byte[] body, Map.Entry<String, String>... headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (var h : headers) {
            map.put(h.getKey(), h.getValue());
        }
        return new WebhookRequest(body, map);
    }
}
