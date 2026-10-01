package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.core.webhook.JetStreamPublisher;
import de.tum.cit.aet.hephaestus.integration.core.webhook.PublishRequest;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * {@code POST /webhooks/gitlab} over HTTP through to the JetStream publish: the verifier sees the
 * bytes and headers GitLab sent, and a rejected delivery is never published. The suite's webhook
 * secret is a {@code whsec_} signing token, which a legacy hook sends verbatim as its token.
 */
class GitlabWebhookIngressIntegrationTest extends BaseIntegrationTest {

    /** Unusual spacing and a non-ASCII character: any re-serialisation would break the signature. */
    private static final byte[] BODY =
            "{ \"object_kind\":\"push\",\n  \"message\":\"Grüße\", \"project\":{\"path_with_namespace\":\"group/project\"} }"
                    .getBytes(StandardCharsets.UTF_8);

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
    void shouldPublishTheExactBytesWhenSignatureIsValid() {
        String timestamp = now();
        String signature = "v1," + sign("msg_valid", timestamp, BODY);

        assertThat(post(BODY, headers -> signed(headers, "msg_valid", timestamp, signature)))
                .isEqualTo(HttpStatus.ACCEPTED);

        ArgumentCaptor<PublishRequest> published = ArgumentCaptor.forClass(PublishRequest.class);
        verify(publisher).publish(published.capture());
        assertThat(published.getValue().body()).isEqualTo(BODY);
        assertThat(published.getValue().subject()).isEqualTo("gitlab.group.project.push");
        assertThat(published.getValue().headers()).containsEntry("X-Gitlab-Event", "Push Hook");
    }

    @Test
    void shouldRejectWhenBodyIsNotTheSignedOne() {
        String timestamp = now();
        String signature = "v1," + sign("msg_tampered", timestamp, BODY);
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8)
                .replace("Grüße", "Grüsse")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(post(tampered, headers -> signed(headers, "msg_tampered", timestamp, signature)))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldRejectWithoutLegacyFallbackWhenSignatureIsBlankOrMalformed() {
        String timestamp = now();
        for (String signature : new String[] {"", sign("msg_malformed", timestamp, BODY), "v2,AAAA"}) {
            assertThat(post(BODY, headers -> {
                        signed(headers, "msg_malformed", timestamp, signature);
                        headers.set("X-Gitlab-Token", secret);
                    }))
                    .as("webhook-signature: '%s'", signature)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldPublishWhenLegacyHookSendsOnlyTheToken() {
        assertThat(post(BODY, headers -> {
                    headers.set("X-Gitlab-Event", "Push Hook");
                    headers.set("X-Gitlab-Token", secret);
                }))
                .isEqualTo(HttpStatus.ACCEPTED);
        verify(publisher).publish(any());
    }

    @Test
    void shouldPublishOnTheConnectionSubjectOnlyWhatTheCredentialProves() {
        GitLabRouteCredential.Issued issued = routeCredential.issue(ROUTE);

        assertThat(postToConnection(7L, issued, ROUTED_BODY, headers -> {
                    headers.set("X-Gitlab-Token", issued.token());
                    headers.set("X-Gitlab-Instance", "https://gitlab.lrz.de");
                    headers.set(GitLabRouteCredential.HEADER_WORKSPACE, "99");
                }))
                .isEqualTo(HttpStatus.ACCEPTED);

        ArgumentCaptor<PublishRequest> published = ArgumentCaptor.forClass(PublishRequest.class);
        verify(publisher).publish(published.capture());
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

        assertThat(postToConnection(8L, issued, ROUTED_BODY, headers -> headers.set("X-Gitlab-Token", token)))
                .as("another connection's endpoint")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
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
        assertThat(postToConnection(7L, issued, ROUTED_BODY, headers -> {
                    headers.set("X-Gitlab-Token", token);
                    headers.set("X-Gitlab-Instance", "https://gitlab.example.com");
                }))
                .as("an instance header contradicting the signed origin")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(ROUTED_BODY, headers -> {
                    headers.set("X-Gitlab-Event", "Issue Hook");
                    headers.set("X-Gitlab-Token", token);
                }))
                .as("the shared endpoint")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postToConnection(7L, issued, ROUTED_BODY, headers -> headers.set("X-Gitlab-Token", secret)))
                .as("the shared secret on a connection endpoint")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldNeverPublishAConnectionSubjectFromTheSharedEndpoint() {
        byte[] body = "{\"object_kind\":\"issue\",\"project\":{\"path_with_namespace\":\"?connection/7\"}}"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(post(body, headers -> {
                    headers.set("X-Gitlab-Event", "Issue Hook");
                    headers.set("X-Gitlab-Token", secret);
                }))
                .isEqualTo(HttpStatus.ACCEPTED);

        ArgumentCaptor<PublishRequest> published = ArgumentCaptor.forClass(PublishRequest.class);
        verify(publisher).publish(published.capture());
        assertThat(published.getValue().subject()).isEqualTo("gitlab.?.7.issue");
        assertThat(published.getValue().headers()).doesNotContainKey(GitLabRouteCredential.HEADER_WORKSPACE);
    }

    private HttpStatusCode postToConnection(
            long connectionId, GitLabRouteCredential.Issued route, byte[] body, Consumer<HttpHeaders> headers) {
        return webTestClient
                .post()
                .uri(GitLabConnectionWebhookController.PATH_PREFIX + connectionId + "/" + route.keyId() + "/"
                        + route.routeId())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers)
                .bodyValue(body)
                .exchange()
                .returnResult(Void.class)
                .getStatus();
    }

    private HttpStatusCode post(byte[] body, Consumer<HttpHeaders> headers) {
        return webTestClient
                .post()
                .uri("/webhooks/gitlab")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers)
                .bodyValue(body)
                .exchange()
                .returnResult(Void.class)
                .getStatus();
    }

    private static void signed(HttpHeaders headers, String id, String timestamp, String signature) {
        headers.set("X-Gitlab-Event", "Push Hook");
        headers.set("webhook-id", id);
        headers.set("webhook-timestamp", timestamp);
        headers.set("webhook-signature", signature);
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private String sign(String id, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring("whsec_".length())), "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
