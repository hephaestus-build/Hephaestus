package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.web.client.RestClient;

class GitHubWebhookRedeliveryTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:10:00Z");

    @Mock
    private GitHubAppTokenService tokens;

    private MockWebServer github;
    private SimpleMeterRegistry meters;
    private GitHubWebhookRedelivery redelivery;

    @BeforeEach
    void setUp() throws IOException {
        github = new MockWebServer();
        github.start();
        meters = new SimpleMeterRegistry();
        redelivery = new GitHubWebhookRedelivery(
                tokens,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meters,
                RestClient.builder(),
                github.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void tearDown() throws IOException {
        github.close();
    }

    @Test
    void shouldRedeliverTheLatestAttemptOfEachEventThatNoAttemptDeliveredWhenTheReceiverFailed() throws Exception {
        configured();
        respond(
                deliveries(
                        redelivery(11, "timed-out", 5, 0),
                        delivery(12, "timed-out", 15, 0),
                        redelivery(13, "recovered", 6, 202),
                        delivery(14, "refused", 7, 400),
                        redelivery(15, "exhausted", 8, 503),
                        redelivery(16, "exhausted", 18, 503),
                        redelivery(17, "exhausted", 28, 503),
                        delivery(18, "exhausted", 38, 503),
                        delivery(19, "unavailable", 12, 503),
                        delivery(20, "recovered", 25, 503),
                        redelivery(21, "original-gone", 30, 503)),
                "<" + github.url("/app/hook/deliveries?per_page=100&cursor=v1_page2") + ">; rel=\"next\"");
        respond(
                deliveries(delivery(22, "last-page", 55, 502), delivery(23, "original-gone", 61, 503)),
                "<" + github.url("/app/hook/deliveries?per_page=100&cursor=v1_page3") + ">; rel=\"next\"");
        for (int i = 0; i < 3; i++)
            github.enqueue(new MockResponse.Builder().code(202).build());

        redelivery.redeliverFailed();

        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0; i < 5; i++) requests.add(github.takeRequest());
        assertThat(requests)
                .extracting(request -> request.getMethod() + " " + request.getTarget())
                .containsExactly(
                        "GET /app/hook/deliveries?per_page=100",
                        "GET /app/hook/deliveries?per_page=100&cursor=v1_page2",
                        "POST /app/hook/deliveries/11/attempts",
                        "POST /app/hook/deliveries/19/attempts",
                        "POST /app/hook/deliveries/22/attempts");
        assertThat(requests)
                .allSatisfy(request ->
                        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer app-jwt"));
        assertThat(github.getRequestCount()).isEqualTo(5);
        assertThat(outcome("requested")).isEqualTo(3);
        assertThat(meters.get("github.webhook.unrecovered").gauge().value()).isEqualTo(1);
    }

    @Test
    void shouldCountARefusedRedeliveryAndKeepTryingTheOthersWhenGitHubRefusesOne() throws Exception {
        configured();
        respond(deliveries(delivery(31, "first", 1, 503), delivery(32, "second", 2, 0)), null);
        github.enqueue(new MockResponse.Builder().code(422).build());
        github.enqueue(new MockResponse.Builder().code(202).build());

        redelivery.redeliverFailed();

        github.takeRequest();
        assertThat(github.takeRequest().getTarget()).isEqualTo("/app/hook/deliveries/31/attempts");
        assertThat(github.takeRequest().getTarget()).isEqualTo("/app/hook/deliveries/32/attempts");
        assertThat(outcome("refused")).isEqualTo(1);
        assertThat(outcome("requested")).isEqualTo(1);
    }

    @Test
    void shouldCountTheRunAsUnavailableWhenGitHubDoesNotListTheDeliveries() {
        configured();
        github.enqueue(new MockResponse.Builder().code(401).build());

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount()).isEqualTo(1);
        assertThat(outcome("unavailable")).isEqualTo(1);
        assertThat(outcome("requested")).isZero();
    }

    @Test
    void shouldAskGitHubNothingWhenTheAppIsNotConfigured() {
        when(tokens.isConfigured()).thenReturn(false);

        redelivery.redeliverFailed();

        assertThat(github.getRequestCount()).isZero();
    }

    private void configured() {
        when(tokens.isConfigured()).thenReturn(true);
        when(tokens.generateAppJWT()).thenReturn("app-jwt");
    }

    private double outcome(String outcome) {
        return meters.get("github.webhook.redelivery")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private void respond(String body, @Nullable String link) {
        var response = new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json");
        if (link != null) response.addHeader("Link", link);
        github.enqueue(response.body(body).build());
    }

    private static String deliveries(String... deliveries) {
        return "[" + String.join(",", deliveries) + "]";
    }

    private static String delivery(long id, String guid, int minutesAgo, int statusCode) {
        return attempt(id, guid, minutesAgo, statusCode, false);
    }

    private static String redelivery(long id, String guid, int minutesAgo, int statusCode) {
        return attempt(id, guid, minutesAgo, statusCode, true);
    }

    private static String attempt(long id, String guid, int minutesAgo, int statusCode, boolean redelivery) {
        return """
                {"id":%d,"guid":"%s","delivered_at":"%s","redelivery":%b,"status":"","status_code":%d,
                 "event":"pull_request","action":"opened"}""".formatted(id, guid, NOW.minus(Duration.ofMinutes(minutesAgo)), redelivery, statusCode);
    }
}
