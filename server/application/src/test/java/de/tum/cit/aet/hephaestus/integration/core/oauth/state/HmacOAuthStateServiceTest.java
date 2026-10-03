package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService.StateBinding;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class HmacOAuthStateServiceTest extends BaseUnitTest {

    private static final String SECRET = "unit-test-secret-with-enough-entropy-32b";

    private static HmacOAuthStateService configuredService(
            @Nullable String oauthSecret, @Nullable String webhookSecret, boolean production) {
        OAuthStateProperties properties =
                new OAuthStateProperties(oauthSecret, Duration.ofMinutes(10), Duration.ofDays(7));
        WebhookProperties webhookProperties = mock(WebhookProperties.class);
        Environment environment = mock(Environment.class);
        if (oauthSecret == null || oauthSecret.isBlank()) {
            when(webhookProperties.secret()).thenReturn(webhookSecret);
            if (webhookSecret == null || webhookSecret.isBlank()) {
                when(environment.matchesProfiles("prod")).thenReturn(production);
            }
        }
        return new HmacOAuthStateService(properties, webhookProperties, environment, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldUseEphemeralArtifactSecretsInsteadOfKnownPlaceholders(String profile, CapturedOutput output) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        var properties = new OAuthStateProperties(null, Duration.ofMinutes(10), Duration.ofDays(7));
        var webhook = mock(WebhookProperties.class);
        var first = new HmacOAuthStateService(properties, webhook, environment, null);
        var second = new HmacOAuthStateService(properties, webhook, environment, null);
        String state = first.issue(42L, IntegrationKind.GITHUB);
        assertThat(first.consume(state).workspaceId()).isEqualTo(42L);
        assertThatThrownBy(() -> second.consume(state)).isInstanceOf(IllegalArgumentException.class);
        assertThat(output.getAll()).doesNotContain("WARN");
        environment.setActiveProfiles(profile, "prod");
        assertThatThrownBy(() -> new HmacOAuthStateService(properties, webhook, environment, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldPreferDedicatedSecretOverWebhookFallback() {
        HmacOAuthStateService issuer = configuredService(SECRET, "different-webhook-secret-of-32b", false);
        HmacOAuthStateService verifier = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));

        assertThat(verifier.consume(issuer.issue(42L, IntegrationKind.GITHUB)).workspaceId())
                .isEqualTo(42L);
    }

    @Test
    void shouldUseWebhookSecretAsFallback() {
        HmacOAuthStateService issuer = configuredService(null, SECRET, false);
        HmacOAuthStateService verifier = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));

        assertThat(verifier.consume(issuer.issue(42L, IntegrationKind.GITHUB)).workspaceId())
                .isEqualTo(42L);
    }

    @Test
    void shouldRejectMissingProductionSecret() {
        assertThatThrownBy(() -> configuredService(null, null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required for OAuth state HMAC");
    }

    @Test
    void shouldGenerateIndependentEphemeralSecretsOutsideProduction() {
        HmacOAuthStateService first = configuredService(null, null, false);
        HmacOAuthStateService second = configuredService(null, null, false);
        String state = first.issue(42L, IntegrationKind.GITHUB);

        assertThatThrownBy(() -> second.consume(state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature of the OAuth state is not correct");
    }

    @Test
    void rejectsCorrectlySignedLegacyNumericLogin() throws Exception {
        String payload = "42|GITHUB|" + Instant.now().getEpochSecond() + "|nonce|NDI";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String signature = encoder.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        String state = encoder.encodeToString((payload + "|" + signature).getBytes(StandardCharsets.UTF_8));
        var service = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        assertThatThrownBy(() -> service.consume(state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The OAuth state has an incorrect format.");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidAccountReference(long accountId) {
        var service = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        assertThatThrownBy(() -> service.issue(42, IntegrationKind.GITHUB, accountId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The actor value of the OAuth state has an incorrect format.");
    }

    @Test
    void issuedStateRoundTrips() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        String state = svc.issue(42L, IntegrationKind.GITHUB);

        StateBinding binding = svc.consume(state);
        assertThat(binding.workspaceId()).isEqualTo(42L);
        assertThat(binding.kind()).isEqualTo(IntegrationKind.GITHUB);
    }

    @Test
    void differentInvocationsProduceDifferentStates() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        String a = svc.issue(1L, IntegrationKind.SLACK);
        String b = svc.issue(1L, IntegrationKind.SLACK);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void tamperedStateRejected() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        String state = svc.issue(42L, IntegrationKind.GITHUB);
        String tampered = state.substring(0, state.length() - 2) + "AA";
        assertThatThrownBy(() -> svc.consume(tampered)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuedWithDifferentSecretRejected() {
        HmacOAuthStateService a = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        HmacOAuthStateService b = HmacOAuthStateService.withoutNonceStore(
                "another-secret-of-equivalent-length-xx", Duration.ofMinutes(10));
        String state = a.issue(42L, IntegrationKind.GITHUB);
        assertThatThrownBy(() -> b.consume(state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature of the OAuth state is not correct");
    }

    @Test
    void blankSecretRejectedAtConstruction() {
        assertThatThrownBy(() -> HmacOAuthStateService.withoutNonceStore("", Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HmacOAuthStateService.withoutNonceStore(null, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankStateRejected() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        assertThatThrownBy(() -> svc.consume("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.consume(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actorAccountIdRoundTripsThroughHmacPayload() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        String state = svc.issue(42L, IntegrationKind.SLACK, 42L);

        StateBinding binding = svc.consume(state);
        assertThat(binding.workspaceId()).isEqualTo(42L);
        assertThat(binding.kind()).isEqualTo(IntegrationKind.SLACK);
        assertThat(binding.actorAccountId()).isEqualTo(42L);
    }

    @Test
    void actorAccountIdIsNullForSystemFlow() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        String state = svc.issue(42L, IntegrationKind.GITHUB);

        StateBinding binding = svc.consume(state);
        assertThat(binding.actorAccountId()).isNull();
    }

    @Test
    void actorAccountIdIsNullWhenExplicitNullPassedToNewOverload() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        String state = svc.issue(42L, IntegrationKind.GITHUB, null);
        StateBinding binding = svc.consume(state);
        assertThat(binding.actorAccountId()).isNull();
    }

    @Test
    void shouldRoundTripLargestAccountId() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        Long actor = Long.MAX_VALUE;
        String state = svc.issue(42L, IntegrationKind.SLACK, actor);
        assertThat(svc.consume(state).actorAccountId()).isEqualTo(actor);
    }

    @Test
    void tamperedActorSegmentRejectedByHmac() {
        HmacOAuthStateService svc = HmacOAuthStateService.withoutNonceStore(SECRET, Duration.ofMinutes(10));
        String state = svc.issue(42L, IntegrationKind.GITHUB, 42L);
        // Flipping a base64 char in the payload (not the signature) MUST still fail —
        // the actor segment is part of the signed payload.
        String tampered = state.substring(0, 4) + "AAAA" + state.substring(8);
        assertThatThrownBy(() -> svc.consume(tampered)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nonce store wired: first consume wins, second is rejected as already-consumed")
    void singleUseEnforcedWithNonceStore() {
        InMemoryNonceStore store = new InMemoryNonceStore();
        HmacOAuthStateService svc = HmacOAuthStateService.withNonceStore(SECRET, Duration.ofMinutes(10), store);
        String state = svc.issue(42L, IntegrationKind.GITHUB);

        // First consume: legit.
        StateBinding binding = svc.consume(state);
        assertThat(binding.workspaceId()).isEqualTo(42L);

        // Second consume of the same state must be rejected — even though HMAC + TTL
        // still validate. This is the load-bearing replay guard.
        assertThatThrownBy(() -> svc.consume(state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already used");
    }

    @Test
    @DisplayName("nonce store: every issue writes a row; consume flips it exactly once")
    void singleUseHonoursPersistence() {
        InMemoryNonceStore store = new InMemoryNonceStore();
        HmacOAuthStateService svc = HmacOAuthStateService.withNonceStore(SECRET, Duration.ofMinutes(10), store);
        svc.issue(1L, IntegrationKind.GITHUB);
        svc.issue(1L, IntegrationKind.GITHUB);
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("nonce store: HMAC failure is detected BEFORE the store is touched")
    void hmacFailureNeverConsumesNonce() {
        InMemoryNonceStore store = new InMemoryNonceStore();
        HmacOAuthStateService svc = HmacOAuthStateService.withNonceStore(SECRET, Duration.ofMinutes(10), store);
        String state = svc.issue(42L, IntegrationKind.GITHUB);
        String tampered = state.substring(0, state.length() - 2) + "AA";

        assertThatThrownBy(() -> svc.consume(tampered)).isInstanceOf(IllegalArgumentException.class);
        // The nonce row must NOT have been consumed — the legitimate caller should
        // still be able to use the real state.
        assertThat(store.consumedCount()).isEqualTo(0);
        StateBinding b = svc.consume(state);
        assertThat(b.workspaceId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("nonce store: expired-by-TTL state is rejected before the store is touched")
    void ttlFailureNeverConsumesNonce() {
        InMemoryNonceStore store = new InMemoryNonceStore();
        // 1ms TTL forces immediate expiry.
        HmacOAuthStateService svc = HmacOAuthStateService.withNonceStore(SECRET, Duration.ofMillis(1), store);
        String state = svc.issue(42L, IntegrationKind.GITHUB);
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        assertThatThrownBy(() -> svc.consume(state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
        assertThat(store.consumedCount()).isEqualTo(0);
    }

    private static final class InMemoryNonceStore extends OAuthStateNonceStore {

        private final Map<String, Boolean> consumed = new ConcurrentHashMap<>();

        InMemoryNonceStore() {
            super(mock(OAuthStateNonceRepository.class));
        }

        @Override
        public void issue(
                @Nullable String nonce,
                long workspaceId,
                IntegrationKind kind,
                Instant issuedAt,
                @Nullable Long actorAccountId) {
            if (nonce == null) return;
            consumed.putIfAbsent(nonce, false);
        }

        @Override
        public boolean tryConsume(@Nullable String nonce, StateBinding binding) {
            if (nonce == null) return false;
            Boolean prior = consumed.get(nonce);
            if (prior == null || prior) return false;
            return consumed.replace(nonce, false, true);
        }

        int size() {
            return consumed.size();
        }

        int consumedCount() {
            int n = 0;
            for (Boolean c : consumed.values()) if (c) n++;
            return n;
        }
    }
}
