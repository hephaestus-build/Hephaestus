package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.gateway.ConversationLoopCredentials;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.fabric8.kubernetes.api.model.authentication.TokenReview;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KubernetesConversationLoopCredentialsTest extends BaseUnitTest {

    private static final String LOOP = "system:serviceaccount:hephaestus:hephaestus-conversation-loop";
    private static final Instant NOW = Instant.parse("2026-10-31T21:00:00Z");

    private final KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final KubernetesConversationLoopCredentials credentials =
            new KubernetesConversationLoopCredentials(client, LOOP, clock);

    @Test
    void shouldAcceptTheLoopsTokenForItsAudienceAndReviewItOnlyOnceWhileItLives() {
        String token = token(NOW.plus(Duration.ofHours(1)));
        reviewed(true, List.of(ConversationLoopCredentials.AUDIENCE), LOOP);

        assertThat(credentials.authenticates(token)).isTrue();
        assertThat(credentials.authenticates(token)).isTrue();

        ArgumentCaptor<TokenReview> sent = ArgumentCaptor.forClass(TokenReview.class);
        verify(client.authentication().v1().tokenReviews(), times(1)).create(sent.capture());
        assertThat(sent.getValue().getSpec().getAudiences()).containsExactly(ConversationLoopCredentials.AUDIENCE);
    }

    @Test
    void shouldRefuseATokenOfAnotherServiceAccountOrAudienceOrOneTheClusterDoesNotAuthenticate() {
        String token = token(NOW.plus(Duration.ofHours(1)));

        reviewed(true, List.of(ConversationLoopCredentials.AUDIENCE), "system:serviceaccount:hephaestus:default");
        assertThat(credentials.authenticates(token)).isFalse();
        reviewed(true, List.of("https://kubernetes.default.svc"), LOOP);
        assertThat(credentials.authenticates(token)).isFalse();
        reviewed(false, List.of(ConversationLoopCredentials.AUDIENCE), LOOP);
        assertThat(credentials.authenticates(token)).isFalse();

        verify(client.authentication().v1().tokenReviews(), times(3)).create(any());
    }

    @Test
    void shouldRefuseWhenTheReviewFailsAndReviewAgainOnTheNextPull() {
        String token = token(NOW.plus(Duration.ofHours(1)));
        when(client.authentication().v1().tokenReviews().create(any()))
                .thenThrow(new KubernetesClientException("timeout"));

        assertThat(credentials.authenticates(token)).isFalse();
        assertThat(credentials.authenticates(token)).isFalse();

        verify(client.authentication().v1().tokenReviews(), times(2)).create(any());
    }

    @Test
    void shouldRefuseAnExpiredOrMalformedTokenOrOneNamingAnotherAccountWithoutAReview() {
        assertThat(credentials.authenticates(token(NOW.minusSeconds(1)))).isFalse();
        assertThat(credentials.authenticates("not-a-jwt")).isFalse();
        assertThat(credentials.authenticates(token(
                        NOW.plus(Duration.ofHours(1)),
                        "system:serviceaccount:hephaestus:other",
                        ConversationLoopCredentials.AUDIENCE)))
                .as("a token that names another service account")
                .isFalse();
        assertThat(credentials.authenticates(
                        token(NOW.plus(Duration.ofHours(1)), LOOP, "https://kubernetes.default.svc")))
                .as("a token of the cluster's own audience")
                .isFalse();

        verify(client.authentication().v1().tokenReviews(), never()).create(any());
    }

    @Test
    void shouldReviewAnAcceptedTokenAgainOnceItExpired() {
        var moving = new MutableClock(NOW, ZoneOffset.UTC);
        var remembering = new KubernetesConversationLoopCredentials(client, LOOP, moving);
        String token = token(NOW.plusSeconds(90));
        reviewed(true, List.of(ConversationLoopCredentials.AUDIENCE), LOOP);
        assertThat(remembering.authenticates(token)).isTrue();

        moving.advance(Duration.ofSeconds(91));

        assertThat(remembering.authenticates(token))
                .as("an expired token is no longer accepted")
                .isFalse();
        verify(client.authentication().v1().tokenReviews(), times(1)).create(any());
    }

    @Test
    void shouldAcceptNoTokenWhenNoLoopServiceAccountIsConfigured() {
        var unconfigured = new KubernetesConversationLoopCredentials(client, "", clock);

        assertThat(unconfigured.authenticates(token(NOW.plus(Duration.ofHours(1)))))
                .isFalse();

        verify(client.authentication().v1().tokenReviews(), never()).create(any());
    }

    private void reviewed(boolean authenticated, List<String> audiences, String user) {
        when(client.authentication().v1().tokenReviews().create(any()))
                .thenReturn(new TokenReviewBuilder()
                        .withNewStatus()
                        .withAuthenticated(authenticated)
                        .withAudiences(audiences)
                        .withNewUser()
                        .withUsername(user)
                        .endUser()
                        .endStatus()
                        .build());
    }

    // A token shaped as a projected service account token; only the cluster's review checks its signature.
    private static String token(Instant expiry) {
        return token(expiry, LOOP, ConversationLoopCredentials.AUDIENCE);
    }

    private static String token(Instant expiry, String subject, String audience) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String claims =
                "{\"exp\":" + expiry.getEpochSecond() + ",\"sub\":\"" + subject + "\",\"aud\":[\"" + audience + "\"]}";
        return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
