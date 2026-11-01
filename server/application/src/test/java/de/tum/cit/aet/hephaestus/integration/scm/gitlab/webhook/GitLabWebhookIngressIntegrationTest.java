package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.core.webhook.JetStreamPublisher;
import de.tum.cit.aet.hephaestus.integration.core.webhook.PublishRequest;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * A connection's GitLab hook over HTTP through to the JetStream publish: the route credential decides, the exact bytes
 * GitLab sent are published, and a rejected delivery is never published. GitLab has no shared endpoint.
 */
class GitLabWebhookIngressIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JetStreamPublisher publisher;

    @Autowired
    private GitLabRouteCredential routeCredential;

    @Value("${hephaestus.webhook.secret}")
    private String secret;

    private static final GitLabRouteCredential.Route ROUTE =
            new GitLabRouteCredential.Route(7L, 3L, "https://gitlab.lrz.de", 42L, "hephaestustest/introcourse");

    private static final byte[] ROUTED_BODY =
            "{\"object_kind\":\"issue\",\"project\":{\"path_with_namespace\":\"hephaestustest/introcourse/demo\"}}"
                    .getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void forgetEarlierPublishes() {
        clearInvocations(publisher);
    }

    @Test
    void shouldHaveNoSharedEndpoint() {
        // Neither the instance secret nor a connection's own credential is accepted on the former shared path.
        GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);
        for (String token : new String[] {secret, issued.token()}) {
            HttpStatusCode status = post(ROUTED_BODY, headers -> {
                headers.set("X-Gitlab-Event", "Issue Hook");
                headers.set("X-Gitlab-Token", token);
            });
            assertThat(status.is4xxClientError()).as("status %s", status).isTrue();
        }
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldPublishOnTheConnectionSubjectOnlyWhatTheCredentialProves() {
        GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);

        postToConnectionResponse(7L, issued, ROUTED_BODY, headers -> {
                    headers.set("X-Gitlab-Token", issued.token());
                    headers.set("X-Gitlab-Instance", "https://gitlab.lrz.de");
                    headers.set(GitLabRouteCredential.HEADER_WORKSPACE, "99");
                })
                .expectStatus()
                .isAccepted()
                .expectBody(String.class)
                .isEqualTo("{\"status\":\"ok\"}");

        ArgumentCaptor<PublishRequest> published = ArgumentCaptor.forClass(PublishRequest.class);
        verify(publisher).publish(published.capture());
        assertThat(published.getValue().body()).isEqualTo(ROUTED_BODY);
        assertThat(published.getValue().subject()).isEqualTo("gitlab.?connection.7.issue");
        assertThat(published.getValue().headers())
                .containsEntry(GitLabRouteCredential.HEADER_WORKSPACE, "3")
                .containsEntry(GitLabRouteCredential.HEADER_GROUP_ID, "42")
                .containsEntry(GitLabRouteCredential.HEADER_GROUP_PATH, "hephaestustest/introcourse")
                .containsEntry(GitLabRouteCredential.HEADER_ORIGIN, "https://gitlab.lrz.de");
    }

    @Test
    void shouldPublishOneEventThatTwoHooksOfTheConnectionDeliverUnderOneDedupKey() {
        GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);
        Consumer<HttpHeaders> sameEvent = headers -> {
            headers.set("X-Gitlab-Token", issued.token());
            headers.set("X-Gitlab-Event", "Issue Hook");
            headers.set("X-Gitlab-Event-UUID", "event-1");
        };

        postToConnection(7L, issued, ROUTED_BODY, sameEvent.andThen(headers -> headers.set("Idempotency-Key", "a")));
        postToConnection(7L, issued, ROUTED_BODY, sameEvent.andThen(headers -> headers.set("Idempotency-Key", "b")));

        ArgumentCaptor<PublishRequest> published = ArgumentCaptor.forClass(PublishRequest.class);
        verify(publisher, times(2)).publish(published.capture());
        String dedupId = published.getAllValues().getFirst().dedupId();
        assertThat(dedupId).startsWith("gitlab-c7-");
        assertThat(published.getAllValues()).extracting(PublishRequest::dedupId).containsOnly(dedupId);
    }

    @Test
    void shouldRejectAConnectionCredentialAnywhereElse() {
        GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);
        String token = issued.token();
        GitLabRouteCredential.Issued otherRoute = routeCredential.issue(new GitLabRouteCredential.Route(
                7L, 3L, "https://gitlab.lrz.de", 42L, "hephaestustest/introcourse-renamed"));

        assertProblem(
                postToConnectionResponse(8L, issued, ROUTED_BODY, headers -> headers.set("X-Gitlab-Token", token)),
                HttpStatus.UNAUTHORIZED,
                "invalid");
        assertThat(postToConnection(7L, otherRoute, ROUTED_BODY, headers -> headers.set("X-Gitlab-Token", token)))
                .as("the URL of another route of the same connection")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postToConnection(
                        7L,
                        new GitLabRouteCredential.Issued(token, "unknown-key", issued.routeId()),
                        ROUTED_BODY,
                        headers -> headers.set("X-Gitlab-Token", token)))
                .as("a key the receiver does not hold")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertProblem(
                postToConnectionResponse(7L, issued, ROUTED_BODY, headers -> {
                    headers.set("X-Gitlab-Token", token);
                    headers.set("X-Gitlab-Instance", "https://gitlab.example.com");
                }),
                HttpStatus.UNAUTHORIZED,
                "invalid");
        assertThat(postToConnection(7L, issued, ROUTED_BODY, headers -> headers.set("X-Gitlab-Token", secret)))
                .as("the instance webhook secret on a connection endpoint")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldReturnProblemDetailsWhenAuthenticatedPayloadIsInvalidJson() {
        byte[] body = "invalid-json-private-payload".getBytes(StandardCharsets.UTF_8);
        GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);
        assertProblem(
                postToConnectionResponse(7L, issued, body, headers -> headers.set("X-Gitlab-Token", issued.token())),
                HttpStatus.BAD_REQUEST,
                "invalid-json");
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldReturnRetryableProblemDetailsWhenPublishFails() {
        doThrow(new JetStreamPublisher.PublishFailedException(
                        new JetStreamPublisher.Failure(JetStreamPublisher.Cause.TIMEOUT, "private publish failure"),
                        1,
                        "gitlab.connection"))
                .when(publisher)
                .publish(any());
        GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);
        try {
            assertProblem(
                    postToConnectionResponse(
                            7L, issued, ROUTED_BODY, headers -> headers.set("X-Gitlab-Token", issued.token())),
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "publish-failed");
            verify(publisher).publish(any());
        } finally {
            reset(publisher);
        }
    }

    private HttpStatusCode postToConnection(
            long connectionId, GitLabRouteCredential.Issued route, byte[] body, Consumer<HttpHeaders> headers) {
        return postToConnectionResponse(connectionId, route, body, headers)
                .returnResult(Void.class)
                .getStatus();
    }

    private WebTestClient.ResponseSpec postToConnectionResponse(
            long connectionId, GitLabRouteCredential.Issued route, byte[] body, Consumer<HttpHeaders> headers) {
        return webTestClient
                .post()
                .uri(GitLabConnectionWebhookController.PATH_PREFIX + connectionId + "/" + route.keyId() + "/"
                        + route.routeId())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers)
                .bodyValue(body)
                .exchange();
    }

    private HttpStatusCode post(byte[] body, Consumer<HttpHeaders> headers) {
        return postResponse(body, headers).returnResult(Void.class).getStatus();
    }

    private WebTestClient.ResponseSpec postResponse(byte[] body, Consumer<HttpHeaders> headers) {
        return webTestClient
                .post()
                .uri("/webhooks/gitlab")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers)
                .bodyValue(body)
                .exchange();
    }

    private static void assertProblem(WebTestClient.ResponseSpec response, HttpStatus status, String detail) {
        response.expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(status.value())
                .jsonPath("$.detail")
                .isEqualTo(detail)
                .jsonPath("$.error")
                .doesNotExist()
                .jsonPath("$.cause")
                .doesNotExist();
    }
}
