package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link WebhookIngestPipeline}. Drives the verify → derive → publish
 * path with mock verifiers + derivers so we can assert what hits JetStream.
 *
 * <p>The tests are deliberately independent of the production GitHub/GitLab verifier
 * implementations — they validate the PIPELINE wiring, not the per-kind crypto.
 * The crypto adapters are covered in their own test classes.
 */
@Tag("unit")
@ExtendWith(OutputCaptureExtension.class)
class WebhookIngestPipelineTest extends BaseUnitTest {

    @Mock
    private JetStreamPublisher publisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("verified webhook is published to JetStream with kind-derived subject + dedup-id")
    void publishesOnVerified() {
        SubjectKeyDeriver deriver = stubDeriver(IntegrationKind.GITHUB, "github.acme.repo.push", "github-DEADBEEF");
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(deriver),
                publisher,
                objectMapper);

        Map<String, String> headers = headers("X-GitHub-Event", "push", "X-GitHub-Delivery", "DEADBEEF");
        ResponseEntity<?> resp = pipeline.handle(
                IntegrationKind.GITHUB, "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8), headers);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isEqualTo(Map.of("status", "ok"));
        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(publisher).publish(captor.capture());
        PublishRequest pr = captor.getValue();
        assertThat(pr.subject()).isEqualTo("github.acme.repo.push");
        assertThat(pr.dedupId()).isEqualTo("github-DEADBEEF");
        assertThat(pr.headers()).containsEntry("Nats-Msg-Id", "github-DEADBEEF");
        assertThat(pr.headers()).containsEntry("X-GitHub-Event", "push");
        assertThat(pr.headers()).containsEntry("X-GitHub-Delivery", "DEADBEEF");
        assertThat(pr.body()).isEqualTo("{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("verified Outline webhook body → outline.<sub>.<event> subject on JetStream")
    void publishesOutlineOnVerified() {
        // Real Outline deriver so the body → subject/dedup derivation is exercised end to end through
        // the pipeline, mirroring the GitLab webhook→NATS path.
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.OUTLINE, new VerificationResult.Verified())),
                List.of(new OutlineSubjectKeyDeriver(objectMapper)),
                publisher,
                objectMapper);

        byte[] body =
                "{\"webhookSubscriptionId\":\"sub-1\",\"event\":\"documents.update\",\"payload\":{\"id\":\"doc-9\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<?> resp = pipeline.handle(IntegrationKind.OUTLINE, body, headers());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(publisher).publish(captor.capture());
        PublishRequest pr = captor.getValue();
        assertThat(pr.subject()).isEqualTo("outline.sub-1.documents~update");
        assertThat(pr.dedupId()).startsWith("outline-");
        assertThat(pr.headers()).containsEntry("Nats-Msg-Id", pr.dedupId());
    }

    @Test
    void invalidSignatureBlocksPublish() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Invalid("signature-mismatch"))),
                List.of(stubDeriver(IntegrationKind.GITHUB, "s", "d")),
                publisher,
                objectMapper);

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.GITHUB, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(resp, HttpStatus.UNAUTHORIZED, "invalid");
        verify(publisher, never()).publish(any());
    }

    @Test
    void missingVerifier() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(List.of(), List.of(), publisher, objectMapper);

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.SLACK, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(resp, HttpStatus.NOT_IMPLEMENTED, "no verifier wired for SLACK");
        verify(publisher, never()).publish(any());
    }

    @Test
    void missingDeriver() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(),
                publisher,
                objectMapper);

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.GITHUB, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(resp, HttpStatus.NOT_IMPLEMENTED, "no subject deriver for GITHUB");
        verify(publisher, never()).publish(any());
    }

    @Test
    void missingPublisher() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.GITHUB, "s", "d")),
                /* publisher */ null,
                objectMapper);

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.GITHUB, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(resp, HttpStatus.SERVICE_UNAVAILABLE, "publisher not wired");
    }

    @Test
    void publisherExceptionMapsTo503AndTheLogNamesItsCause(CapturedOutput output) {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITLAB, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.GITLAB, "gitlab.foo.bar.push", "gitlab-uuid")),
                publisher,
                objectMapper);
        doThrow(new JetStreamPublisher.PublishFailedException(
                        new JetStreamPublisher.Failure(JetStreamPublisher.Cause.NO_RESPONDERS, "503"),
                        3,
                        "gitlab.foo.bar.push"))
                .when(publisher)
                .publish(any());

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.GITLAB, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(resp, HttpStatus.SERVICE_UNAVAILABLE, "publish-failed");
        assertThat(output.getAll()).contains("cause=no_responders attempts=3");
    }

    @Test
    void invalidJsonBlocksPublish() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.GITHUB, "s", "d")),
                publisher,
                objectMapper);

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.GITHUB, "not json".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(resp, HttpStatus.BAD_REQUEST, "invalid-json");
        verify(publisher, never()).publish(any());
    }

    @Test
    void publishGateCanDropVerifiedWebhookBeforeDurablePublish() {
        WebhookPublishGate gate = new WebhookPublishGate() {
            @Override
            public IntegrationKind kind() {
                return IntegrationKind.SLACK;
            }

            @Override
            public Decision evaluate(JsonNode payload, Map<String, String> headers) {
                return Decision.drop("not-consented");
            }
        };
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.SLACK, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.SLACK, "slack.T.C.message", "slack-Ev1")),
                publisher,
                objectMapper,
                List.of(gate));

        ResponseEntity<?> resp = pipeline.handle(
                IntegrationKind.SLACK,
                "{\"event\":{\"type\":\"message\"}}".getBytes(StandardCharsets.UTF_8),
                headers());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isEqualTo(Map.of("status", "dropped"));
        verify(publisher, never()).publishFast(any(), any());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("stale-timestamp verdict → 401")
    void shouldRejectWith401WhenTimestampIsStale() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.SLACK, new VerificationResult.StaleTimestamp(900L))),
                List.of(),
                publisher,
                objectMapper);

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.SLACK, "{}".getBytes(StandardCharsets.UTF_8), headers());

        // All auth failures (missing / bad signature / stale) collapse to opaque 401 so
        // attackers can't probe the signing scheme through status-code side channels.
        assertProblem(resp, HttpStatus.UNAUTHORIZED, "stale-timestamp");
        verify(publisher, never()).publish(any());
    }

    @Test
    void respondImmediatelyShortCircuits() {
        byte[] echoBody = "challenge-ack".getBytes(StandardCharsets.UTF_8);
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(
                        IntegrationKind.SLACK,
                        new VerificationResult.RespondImmediately(
                                200, "text/plain", echoBody, Map.of("X-Challenge", "ack")))),
                List.of(),
                publisher,
                objectMapper);

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.SLACK, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(resp.getHeaders().getFirst("X-Challenge")).isEqualTo("ack");
        assertThat(resp.getBody()).isEqualTo(echoBody);
        verify(publisher, never()).publish(any());
        verify(publisher, never()).publishFast(any(), any());
    }

    @Test
    void shouldLogOnlyTheFailureTypeWhenAVerifierThrowsOnAnUnauthenticatedBody() {
        WebhookSignatureVerifier throwing = new WebhookSignatureVerifier() {
            @Override
            public IntegrationKind kind() {
                return IntegrationKind.SLACK;
            }

            @Override
            public VerificationResult verify(WebhookRequest request) {
                throw new IllegalArgumentException(
                        "Unexpected token in " + new String(request.body(), StandardCharsets.UTF_8));
            }
        };
        WebhookIngestPipeline pipeline =
                new WebhookIngestPipeline(List.of(throwing), List.of(), publisher, objectMapper);
        Logger logger = (Logger) LoggerFactory.getLogger(WebhookIngestPipeline.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ResponseEntity<?> response;
        try {
            response = pipeline.handle(
                    IntegrationKind.SLACK,
                    "{\"text\":\"private-message-canary\"}".getBytes(StandardCharsets.UTF_8),
                    headers());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertProblem(response, HttpStatus.UNAUTHORIZED, "invalid");
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains(IllegalArgumentException.class.getName())
                    .doesNotContain("canary");
            assertThat(event.getThrowableProxy()).isNull();
        });
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldKeepMissingSignatureCoarseAndBlockPublish() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.MissingSignature())),
                List.of(),
                publisher,
                objectMapper);

        ResponseEntity<?> response = pipeline.handle(IntegrationKind.GITHUB, new byte[0], headers());

        assertProblem(response, HttpStatus.UNAUTHORIZED, "missing-signature");
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldReturnSafeDetailWhenRequestBodyCannotBeRead() throws IOException {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(List.of(), List.of(), publisher, objectMapper);
        WebhookController controller = new WebhookController(pipeline, new IntegrationKindRouting());
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new IOException("private request data")).when(request).getInputStream();

        ResponseEntity<?> response = controller.ingest("github", request);

        assertProblem(response, HttpStatus.BAD_REQUEST, "could not read body");
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldUseFastPublishTimeoutAndReturn503WhenSlackPublishFails() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.SLACK, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.SLACK, "slack.T.C.message", "slack-Ev1")),
                publisher,
                objectMapper);
        doThrow(new JetStreamPublisher.PublishFailedException(
                        new JetStreamPublisher.Failure(JetStreamPublisher.Cause.TIMEOUT, "private failure data"),
                        1,
                        "slack.T.C.message"))
                .when(publisher)
                .publishFast(any(), any());

        ResponseEntity<?> response =
                pipeline.handle(IntegrationKind.SLACK, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(response, HttpStatus.SERVICE_UNAVAILABLE, "publish-failed");
        verify(publisher).publishFast(any(), eq(Duration.ofSeconds(2)));
        verify(publisher, never()).publish(any());
    }

    @Test
    void duplicateVerifierRejected() {
        assertThatThrownBy(() -> new WebhookIngestPipeline(
                        List.of(
                                stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified()),
                                stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                        List.of(),
                        publisher,
                        objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate WebhookSignatureVerifier");
    }

    @Test
    void duplicateDeriverRejected() {
        assertThatThrownBy(() -> new WebhookIngestPipeline(
                        List.of(),
                        List.of(
                                stubDeriver(IntegrationKind.GITHUB, "s", "d"),
                                stubDeriver(IntegrationKind.GITHUB, "s", "d")),
                        publisher,
                        objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate SubjectKeyDeriver");
    }

    // Stubs

    private static void assertProblem(ResponseEntity<?> response, HttpStatus status, String detail) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problem -> {
            assertThat(problem.getStatus()).isEqualTo(status.value());
            assertThat(problem.getDetail()).isEqualTo(detail);
        });
    }

    private static WebhookSignatureVerifier stubVerifier(IntegrationKind kind, VerificationResult result) {
        return new WebhookSignatureVerifier() {
            @Override
            public IntegrationKind kind() {
                return kind;
            }

            @Override
            public VerificationResult verify(WebhookRequest request) {
                return result;
            }
        };
    }

    private static SubjectKeyDeriver stubDeriver(IntegrationKind kind, String subject, String dedupId) {
        return new SubjectKeyDeriver() {
            @Override
            public IntegrationKind kind() {
                return kind;
            }

            @Override
            public String deriveSubject(JsonNode payload, Map<String, String> headers) {
                return subject;
            }

            @Override
            public String deriveDedupKey(byte[] body, Map<String, String> headers) {
                return dedupId;
            }
        };
    }

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
