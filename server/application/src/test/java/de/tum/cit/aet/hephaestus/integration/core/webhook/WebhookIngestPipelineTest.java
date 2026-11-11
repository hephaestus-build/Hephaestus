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
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
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
    private ProviderEventReceiver publisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName(
            "a large delivery with a valid signature gets 503 when its parse does not fit, and nothing is published")
    void refusesALargeDeliveryThatDoesNotFitAfterItsSignature() {
        WebhookInFlightBudget budget = new WebhookInFlightBudget(1024, 1024, Duration.ofMillis(1));
        MeterRegistry meters = new SimpleMeterRegistry();
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.GITHUB, "github.acme.repo.push", "github-DEADBEEF")),
                publisher,
                objectMapper,
                List.of(),
                budget,
                meters);
        byte[] large = new byte[(int) WebhookInFlightBudget.SMALL_BODY_BYTES + 1];

        try (WebhookInFlightBudget.Holding held = budget.forPublishing()) {
            assertThat(held.add(1024)).isTrue();

            ResponseEntity<?> refused =
                    pipeline.handle(IntegrationKind.GITHUB, large, headers("X-GitHub-Event", "push"));

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        }
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
        assertThat(meters.get(IntegrationCoreMetrics.WEBHOOK_REJECTED)
                        .tag("reason", "busy")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a small delivery with a valid signature does not wait for the budget")
    void aSmallDeliveryDoesNotCountAgainstTheBudget() {
        WebhookInFlightBudget budget = new WebhookInFlightBudget(1024, 1024, Duration.ofMillis(1));
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.GITHUB, "github.acme.repo.push", "github-DEADBEEF")),
                publisher,
                objectMapper,
                List.of(),
                budget,
                new SimpleMeterRegistry());

        try (WebhookInFlightBudget.Holding held = budget.forPublishing()) {
            assertThat(held.add(1024)).isTrue();

            ResponseEntity<?> accepted = pipeline.handle(
                    IntegrationKind.GITHUB,
                    "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8),
                    headers("X-GitHub-Event", "push"));

            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
    }

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
        ArgumentCaptor<Map<String, String>> stored = headersCaptor();
        verify(publisher)
                .receive(
                        eq(IntegrationKind.GITHUB),
                        eq("github.acme.repo.push"),
                        eq("github-DEADBEEF"),
                        stored.capture(),
                        eq("{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(stored.getValue()).containsEntry("X-GitHub-Event", "push");
        assertThat(stored.getValue()).containsEntry("X-GitHub-Delivery", "DEADBEEF");
    }

    @Test
    @DisplayName("verified Outline webhook body → outline.<sub>.<event> subject on JetStream")
    void publishesOutlineOnVerified() {
        // Real Outline deriver so the body → subject/dedup derivation is exercised end to end through
        // the pipeline.
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
        ArgumentCaptor<String> delivery = ArgumentCaptor.forClass(String.class);
        verify(publisher)
                .receive(
                        eq(IntegrationKind.OUTLINE),
                        eq("outline.sub-1.documents~update"),
                        delivery.capture(),
                        any(),
                        any());
        assertThat(delivery.getValue()).startsWith("outline-");
    }

    @Test
    void anAuthenticatedRouteNeedsNoSharedPathDeriver() {
        // GitLab's connection route derives its own subject and dedup id; the kind has no SubjectKeyDeriver bean.
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(List.of(), List.of(), publisher, objectMapper);

        ResponseEntity<?> resp = pipeline.publishAuthenticated(
                IntegrationKind.GITLAB,
                "{\"object_kind\":\"push\"}".getBytes(StandardCharsets.UTF_8),
                headers(),
                payload -> "gitlab.?connection.7." + payload.path("object_kind").asString(),
                "gitlab-c7-abc",
                Map.of("Hephaestus-Route-Workspace", "3"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<Map<String, String>> stored = headersCaptor();
        verify(publisher)
                .receive(
                        eq(IntegrationKind.GITLAB),
                        eq("gitlab.?connection.7.push"),
                        eq("gitlab-c7-abc"),
                        stored.capture(),
                        any());
        assertThat(stored.getValue()).containsEntry("Hephaestus-Route-Workspace", "3");
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
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
    }

    @Test
    void missingVerifier() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(List.of(), List.of(), publisher, objectMapper);

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.SLACK, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(resp, HttpStatus.NOT_IMPLEMENTED, "no verifier wired for SLACK");
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
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
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
    }

    @Test
    void shouldAnswer503AndNameTheCauseWhenTheInboxCannotStore(CapturedOutput output) {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.GITHUB, "github.foo.bar.push", "github-uuid")),
                publisher,
                objectMapper);
        doThrow(new CannotGetJdbcConnectionException("database down"))
                .when(publisher)
                .receive(any(), any(), any(), any(), any());

        ResponseEntity<?> resp =
                pipeline.handle(IntegrationKind.GITHUB, "{}".getBytes(StandardCharsets.UTF_8), headers());

        assertProblem(resp, HttpStatus.SERVICE_UNAVAILABLE, "store-failed");
        assertThat(output.getAll()).contains("cause=org.springframework.jdbc.CannotGetJdbcConnectionException");
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
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("payloadsThatCannotBeStoredAsUtf8Text")
    void shouldRejectPayloadsThatCannotBeReadAsPostgresUtf8Text(byte[] body) {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.GITHUB, "github.foo.bar.push", "github-uuid")),
                publisher,
                objectMapper);

        var response = pipeline.handle(IntegrationKind.GITHUB, body, headers());

        assertProblem(response, HttpStatus.BAD_REQUEST, "invalid-json");
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
    }

    private static Stream<byte[]> payloadsThatCannotBeStoredAsUtf8Text() {
        return Stream.of(
                "{}".getBytes(StandardCharsets.UTF_16),
                "{}".getBytes(StandardCharsets.UTF_16BE),
                "{}".getBytes(StandardCharsets.UTF_16LE),
                new byte[] {0, 0, 0, '{', 0, 0, 0, '}'},
                new byte[] {'{', 0, 0, 0, '}', 0, 0, 0},
                new byte[] {'{', '}', 0},
                "{\"x\":\"\\u0000\"}".getBytes(StandardCharsets.UTF_8),
                "{\"\\u0000\":\"value\"}".getBytes(StandardCharsets.UTF_8),
                "{\"x\":[{\"nested\":\"\\ud800\"}]}".getBytes(StandardCharsets.UTF_8),
                "{\"x\":\"\\udc00\"}".getBytes(StandardCharsets.UTF_8),
                new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc0, (byte) 0xaf, '"', '}'},
                new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xed, (byte) 0xa0, (byte) 0x80, '"', '}'});
    }

    @Test
    void shouldKeepValidNonAsciiUtf8PayloadsUnchanged() {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(
                List.of(stubVerifier(IntegrationKind.GITHUB, new VerificationResult.Verified())),
                List.of(stubDeriver(IntegrationKind.GITHUB, "github.foo.bar.push", "github-uuid")),
                publisher,
                objectMapper);
        byte[] body = "{\"message\":\"Grüße 🌍 \\\\u0000\"}".getBytes(StandardCharsets.UTF_8);

        var response = pipeline.handle(IntegrationKind.GITHUB, body, headers());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(publisher).receive(any(), any(), any(), any(), eq(body));
    }

    @Test
    void shouldAnswer503AndPublishNothingWhenAPublishGateCannotRead() {
        WebhookPublishGate gate = new WebhookPublishGate() {
            @Override
            public IntegrationKind kind() {
                return IntegrationKind.SLACK;
            }

            @Override
            public Decision evaluate(JsonNode payload, Map<String, String> headers) {
                throw new CannotGetJdbcConnectionException("database down");
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

        assertProblem(resp, HttpStatus.SERVICE_UNAVAILABLE, "gate-unavailable");
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
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
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
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
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
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
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
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
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
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
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnSafeDetailWhenRequestBodyCannotBeRead() throws IOException {
        WebhookIngestPipeline pipeline = new WebhookIngestPipeline(List.of(), List.of(), publisher, objectMapper);
        WebhookController controller = new WebhookController(pipeline, new IntegrationKindRouting());
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new IOException("private request data")).when(request).getInputStream();

        ResponseEntity<?> response = controller.ingest("github", request);

        assertProblem(response, HttpStatus.BAD_REQUEST, "could not read body");
        verify(publisher, never()).receive(any(), any(), any(), any(), any());
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

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> headersCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
