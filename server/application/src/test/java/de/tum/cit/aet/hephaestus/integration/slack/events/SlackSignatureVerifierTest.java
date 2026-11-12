package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SlackSignatureVerifierTest extends BaseUnitTest {

    private static final String SECRET = "8f742231b10e4f81b3e2c9a7d6541abc";
    private static final long NOW = 1_700_000_000L;

    @Test
    void validSignatureWithinWindowVerifies() {
        byte[] body = body("token=abc&command=%2Fmentor");
        String ts = Long.toString(NOW);
        String sig = sign(SECRET, ts, body);

        assertThat(newVerifier(SECRET).verify(ts, sig, body, NOW)).isTrue();
    }

    @Test
    void timestampAtWindowEdgeAccepted() {
        byte[] body = body("{}");
        String ts = Long.toString(NOW - 300);
        String sig = sign(SECRET, ts, body);

        assertThat(newVerifier(SECRET).verify(ts, sig, body, NOW)).isTrue();
    }

    @Test
    void unconfiguredSecretFailsFast() {
        assertThatThrownBy(() -> newVerifier("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing-secret is blank");
    }

    private static ApplicationContextRunner slackEnabledWithRoles(boolean server, boolean webhook) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SlackSignatureVerifier.class)
                .withPropertyValues(
                        "hephaestus.integration.slack.enabled=true",
                        RuntimeRole.SERVER_PROPERTY + "=" + server,
                        RuntimeRole.WEBHOOK_PROPERTY + "=" + webhook);
    }

    @ParameterizedTest(name = "server={0}, webhook={1}")
    @CsvSource({"true,false", "false,true", "true,true"})
    @DisplayName("a process that receives Slack verifies it, on either role")
    void serverOrWebhookRoleCreatesTheVerifier(boolean server, boolean webhook) {
        slackEnabledWithRoles(server, webhook)
                .withPropertyValues("hephaestus.integration.slack.signing-secret=" + SECRET)
                .run(context -> assertThat(context).hasSingleBean(SlackSignatureVerifier.class));
    }

    @Test
    @DisplayName("the server role refuses to boot with Slack enabled and no signing secret")
    void serverRoleWithoutSigningSecretFailsFast() {
        slackEnabledWithRoles(true, false)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("signing-secret is blank"));
    }

    private static Stream<Arguments> rejectedSignatures() {
        byte[] signedBody = body("{\"text\":\"hello\"}");
        String validTs = Long.toString(NOW);
        String validSig = sign(SECRET, validTs, signedBody);
        byte[] tamperedBody = body("{\"text\":\"HELLO\"}");

        String expiredTs = Long.toString(NOW - 301);
        byte[] expiredBody = body("token=abc&command=%2Fmentor");
        String expiredSig = sign(SECRET, expiredTs, expiredBody);

        String nonNumericTs = "not-a-number";
        byte[] plainBody = body("{}");
        String nonNumericSig = sign(SECRET, nonNumericTs, plainBody);

        return Stream.of(
                Arguments.of("expired timestamp outside replay window", expiredTs, expiredSig, expiredBody),
                Arguments.of("tampered body", validTs, validSig, tamperedBody),
                Arguments.of("wrong signature", validTs, "v0=deadbeef", plainBody),
                Arguments.of("non-numeric timestamp", nonNumericTs, nonNumericSig, plainBody));
    }

    @ParameterizedTest(name = "{0} is rejected")
    @MethodSource("rejectedSignatures")
    void invalidSignatureRejected(String description, String ts, String sig, byte[] body) {
        assertThat(newVerifier(SECRET).verify(ts, sig, body, NOW)).isFalse();
    }

    @Test
    void nullTimestampOrSignatureRejected() {
        byte[] body = body("{}");
        String ts = Long.toString(NOW);
        String sig = sign(SECRET, ts, body);

        SlackSignatureVerifier verifier = newVerifier(SECRET);
        assertThat(verifier.verify(null, sig, body, NOW)).isFalse();
        assertThat(verifier.verify(ts, null, body, NOW)).isFalse();
        assertThat(verifier.verify(ts, sig, null, NOW)).isFalse();
    }

    private static SlackSignatureVerifier newVerifier(String secret) {
        return new SlackSignatureVerifier(secret);
    }

    private static String sign(String secret, String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] body(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
