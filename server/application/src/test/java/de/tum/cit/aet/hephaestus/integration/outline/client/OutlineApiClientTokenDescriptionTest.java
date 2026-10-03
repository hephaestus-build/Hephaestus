package de.tum.cit.aet.hephaestus.integration.outline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Pins {@code apiKeys.list} → "describe my own token". Outline never returns a key's value after
 * creation, so the client identifies its own key by matching the {@code last4} suffix of the token it
 * holds. The two non-obvious behaviors are the point: a 403 (the key is scoped away from
 * {@code apiKeys.list}, or its owner may not see it) is NOT an error — the token still syncs content,
 * so the metadata is simply absent; any other failure propagates.
 */
class OutlineApiClientTokenDescriptionTest extends BaseUnitTest {

    private static final String SERVER_URL = "https://wiki.example.com";
    private static final String TOKEN = "ol_api_secret_ab12";

    /** Two keys, one of which ends in the token's last four characters. */
    private static final String API_KEYS_BODY =
            "{\"data\":[" + "{\"id\":\"k-other\",\"name\":\"CI bot\",\"last4\":\"zz99\"},"
                    + "{\"id\":\"k-mine\",\"name\":\"Hephaestus\",\"last4\":\"ab12\","
                    + "\"expiresAt\":\"2026-12-01T10:00:00.000Z\",\"lastActiveAt\":\"2026-07-13T08:30:00.000Z\"}"
                    + "]}";

    private static OutlineApiClient clientRespondingWith(
            int status, String body, AtomicReference<ClientRequest> captured) {
        ExchangeFunction exchange = request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build());
        };
        return new OutlineApiClient(
                CircuitBreaker.ofDefaults("outlineRestApi"),
                Retry.ofDefaults("outlineRestApi"),
                WebClient.builder().exchangeFunction(exchange).build(),
                new OutlineOriginPolicy(Set.of("https://wiki.example.com")));
    }

    @Test
    void describeToken_matchesItsOwnKeyByLast4() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        OutlineApiClient client = clientRespondingWith(200, API_KEYS_BODY, captured);

        Optional<OutlineApiClient.OutlineTokenDescription> described = client.describeToken(SERVER_URL, TOKEN);

        ClientRequest request = captured.get();
        assertNotNull(request);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url().toString()).isEqualTo("https://wiki.example.com/api/apiKeys.list");
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
        assertThat(described).isPresent();
        assertThat(described.get().name()).isEqualTo("Hephaestus"); // not the foreign "CI bot" key
        assertThat(described.get().last4()).isEqualTo("ab12");
        assertThat(described.get().expiresAt()).isEqualTo(Instant.parse("2026-12-01T10:00:00Z"));
        assertThat(described.get().lastActiveAt()).isEqualTo(Instant.parse("2026-07-13T08:30:00Z"));
    }

    @Test
    void describeToken_isEmptyWhenNoKeyMatchesTheTokensSuffix() {
        // The key exists upstream but belongs to someone else — matching on name or position would
        // hand the admin another key's expiry.
        OutlineApiClient client = clientRespondingWith(
                200,
                "{\"data\":[{\"id\":\"k-other\",\"name\":\"CI bot\",\"last4\":\"zz99\"}]}",
                new AtomicReference<>());

        assertThat(client.describeToken(SERVER_URL, TOKEN)).isEmpty();
    }

    @Test
    void describeToken_isEmptyOnForbidden_becauseAnOutOfScopeKeyStillSyncs() {
        OutlineApiClient client =
                clientRespondingWith(403, "{\"error\":\"authorization_error\"}", new AtomicReference<>());

        assertThat(client.describeToken(SERVER_URL, TOKEN)).isEmpty();
    }

    @Test
    void describeToken_propagatesAnyOtherFailure() {
        // A revoked token (401 authentication_required) is a real problem — it must not be laundered
        // into "no metadata available".
        OutlineApiClient client =
                clientRespondingWith(401, "{\"error\":\"authentication_required\"}", new AtomicReference<>());

        assertThatThrownBy(() -> client.describeToken(SERVER_URL, TOKEN))
                .isInstanceOf(OutlineApiException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void describeToken_isEmptyWhenOutlineReturnsNoKeys() {
        OutlineApiClient client = clientRespondingWith(200, "{}", new AtomicReference<>());

        assertThat(client.describeToken(SERVER_URL, TOKEN)).isEmpty();
    }
}
