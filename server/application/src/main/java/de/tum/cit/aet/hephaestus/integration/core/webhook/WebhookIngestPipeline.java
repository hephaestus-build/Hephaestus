package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound webhook pipeline: verify → derive → publish.
 *
 * <p>Verification can short-circuit with {@code RespondImmediately} (Slack
 * {@code url_verification}); on {@code Verified} the
 * pipeline derives the NATS subject + dedup-id via the per-kind {@link SubjectKeyDeriver}
 * and publishes through {@link JetStreamPublisher}.
 *
 * <p>Error responses carry only a coarse category ({@code "invalid"} / {@code "missing-signature"} /
 * {@code "stale-timestamp"}). The verifier's {@code Invalid.reason} — which distinguishes a missing secret from a
 * signature mismatch from a malformed header — is logged server-side only; echoing it would hand attacker probes a
 * side channel into the signing scheme.
 */
@Component
public class WebhookIngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestPipeline.class);

    static final String NATS_MSG_ID = "Nats-Msg-Id";
    private static final Duration SLACK_PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private final Map<IntegrationKind, WebhookSignatureVerifier> verifiersByKind;
    private final Map<IntegrationKind, SubjectKeyDeriver> deriversByKind;
    private final Map<IntegrationKind, WebhookPublishGate> publishGatesByKind;

    @Nullable
    private final JetStreamPublisher jetStreamPublisher;

    private final ObjectMapper objectMapper;

    public WebhookIngestPipeline(
            List<WebhookSignatureVerifier> verifiers,
            List<SubjectKeyDeriver> derivers,
            @Nullable JetStreamPublisher jetStreamPublisher,
            ObjectMapper objectMapper) {
        this(verifiers, derivers, jetStreamPublisher, objectMapper, List.of());
    }

    @Autowired
    public WebhookIngestPipeline(
            List<WebhookSignatureVerifier> verifiers,
            List<SubjectKeyDeriver> derivers,
            @Nullable JetStreamPublisher jetStreamPublisher,
            ObjectMapper objectMapper,
            List<WebhookPublishGate> publishGates) {
        this.verifiersByKind = verifiers.stream()
                .collect(Collectors.toUnmodifiableMap(WebhookSignatureVerifier::kind, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate WebhookSignatureVerifier for kind=" + a.kind());
                }));
        this.deriversByKind = derivers.stream()
                .collect(Collectors.toUnmodifiableMap(SubjectKeyDeriver::kind, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate SubjectKeyDeriver for kind=" + a.kind());
                }));
        this.publishGatesByKind = publishGates.stream()
                .collect(Collectors.toUnmodifiableMap(WebhookPublishGate::kind, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate WebhookPublishGate for kind=" + a.kind());
                }));
        this.jetStreamPublisher = jetStreamPublisher;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<?> handle(IntegrationKind kind, HttpServletRequest req) throws IOException {
        byte[] body = req.getInputStream().readAllBytes();
        Map<String, String> headers = readHeaders(req);
        return handle(kind, body, headers);
    }

    /** Pre-read overload for tests and call sites that already consumed the servlet body. */
    public ResponseEntity<?> handle(IntegrationKind kind, byte[] body, Map<String, String> headers) {
        WebhookSignatureVerifier verifier = verifiersByKind.get(kind);
        if (verifier == null) {
            // No verifier wired — kind is allow-listed but not yet implemented.
            log.warn("No WebhookSignatureVerifier registered for kind={}; rejecting", kind);
            return problem(HttpStatus.NOT_IMPLEMENTED, "no verifier wired for " + kind);
        }

        WebhookRequest request = new WebhookRequest(body, headers);
        VerificationResult result;
        try {
            result = verifier.verify(request);
        } catch (RuntimeException e) {
            // The type only: a verifier that fails while reading the body can quote that unauthenticated body.
            log.warn(
                    "Verifier {} threw for kind={}: {}",
                    verifier.getClass().getSimpleName(),
                    kind,
                    e.getClass().getName());
            return problem(HttpStatus.UNAUTHORIZED, "invalid");
        }

        return switch (result) {
            case VerificationResult.Verified() -> publish(kind, body, headers, null);
            case VerificationResult.RespondImmediately r -> respondImmediately(r);
            case VerificationResult.StaleTimestamp s -> {
                log.debug("Webhook rejected for kind={}: stale timestamp drift={}s", kind, s.driftSeconds());
                yield problem(HttpStatus.UNAUTHORIZED, "stale-timestamp");
            }
            case VerificationResult.Invalid i -> {
                log.warn("Webhook rejected for kind={}: {}", kind, i.reason());
                yield problem(HttpStatus.UNAUTHORIZED, "invalid");
            }
            case VerificationResult.MissingSignature() -> problem(HttpStatus.UNAUTHORIZED, "missing-signature");
        };
    }

    /**
     * Publishes a delivery its caller has authenticated for one route, on the subject {@code subject} derives from the
     * payload, under {@code dedupId} and carrying {@code routeHeaders}. Those headers are written here, never copied
     * from the request, so a sender cannot supply them.
     */
    public ResponseEntity<?> publishAuthenticated(
            IntegrationKind kind,
            byte[] body,
            Map<String, String> headers,
            Function<JsonNode, String> subject,
            String dedupId,
            Map<String, String> routeHeaders) {
        return publish(kind, body, headers, new Route(subject, dedupId, routeHeaders));
    }

    private record Route(Function<JsonNode, String> subject, String dedupId, Map<String, String> headers) {}

    private ResponseEntity<?> publish(
            IntegrationKind kind, byte[] body, Map<String, String> headers, @Nullable Route route) {
        if (jetStreamPublisher == null) {
            // No publisher bean — the webhook runtime role is disabled. Vendor will retry.
            // 503 is the right surface here: the verification succeeded, but the downstream
            // pipe is intentionally not wired on this pod.
            log.warn("WebhookIngestPipeline: verified {} webhook but no JetStreamPublisher bean — replying 503", kind);
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "publisher not wired");
        }
        SubjectKeyDeriver deriver = deriversByKind.get(kind);
        if (route == null && deriver == null) {
            // Kind has a verifier but no derivation — surface as NOT_IMPLEMENTED so the
            // operator notices the gap rather than silently dropping into a default subject.
            log.warn("WebhookIngestPipeline: no SubjectKeyDeriver wired for kind={}", kind);
            return problem(HttpStatus.NOT_IMPLEMENTED, "no subject deriver for " + kind);
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (Exception e) {
            return problem(HttpStatus.BAD_REQUEST, "invalid-json");
        }

        WebhookPublishGate gate = publishGatesByKind.get(kind);
        if (gate != null) {
            WebhookPublishGate.Decision decision;
            try {
                decision = gate.evaluate(payload, headers);
            } catch (ProjectedKeys.UnavailableException e) {
                // The gate cannot read what it decides on: nothing is published, and the provider retries.
                log.warn("Publish gate for kind={} unavailable: bucket={}, cause={}", kind, e.bucket(), e.causeClass());
                return problem(HttpStatus.SERVICE_UNAVAILABLE, "gate-unavailable");
            }
            if (!decision.publish()) {
                log.info("Dropped {} webhook before durable publish: {}", kind, sanitizeForLog(decision.reason()));
                return ResponseEntity.accepted().body(Map.of("status", "dropped"));
            }
        }

        String subject = route != null
                ? route.subject().apply(payload)
                : Objects.requireNonNull(deriver).deriveSubject(payload, headers);
        String dedupId = route != null
                ? route.dedupId()
                : Objects.requireNonNull(deriver).deriveDedupKey(body, headers);

        // Nats-Msg-Id also rides in JetStreamPublisher's PublishOptions; duplicating it on the
        // headers keeps the wire trace self-describing.
        Map<String, String> outboundHeaders = new LinkedHashMap<>();
        passthroughHeader(outboundHeaders, headers, "X-GitHub-Event");
        passthroughHeader(outboundHeaders, headers, "X-GitHub-Delivery");
        passthroughHeader(outboundHeaders, headers, "X-Gitlab-Event");
        passthroughHeader(outboundHeaders, headers, "X-Gitlab-Webhook-UUID");
        if (route != null) {
            outboundHeaders.putAll(route.headers());
        }
        outboundHeaders.put(NATS_MSG_ID, dedupId);

        try {
            PublishRequest request = new PublishRequest(subject, dedupId, outboundHeaders, body);
            if (kind == IntegrationKind.SLACK) {
                jetStreamPublisher.publishFast(request, SLACK_PUBLISH_TIMEOUT);
            } else {
                jetStreamPublisher.publish(request);
            }
        } catch (JetStreamPublisher.PublishFailedException e) {
            log.atError()
                    .addKeyValue("event.name", "webhook.publish.failed")
                    .addKeyValue("webhook.publish.reason", e.failureCause().label())
                    .addKeyValue("webhook.publish.attempts", e.attempts())
                    .log(
                            "Webhook not stored for kind={} subject={}: cause={} attempts={} detail={}",
                            kind,
                            sanitizeForLog(subject),
                            e.failureCause().label(),
                            e.attempts(),
                            sanitizeForLog(e.detail()));
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "publish-failed");
        }
        log.info(
                "Published {} webhook to NATS: subject={} dedupId={}",
                kind,
                sanitizeForLog(subject),
                sanitizeForLog(dedupId));
        return ResponseEntity.accepted().body(Map.of("status", "ok"));
    }

    public static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    private static void passthroughHeader(Map<String, String> out, Map<String, String> in, String name) {
        String value = headerCaseInsensitive(in, name);
        if (value != null && !value.isBlank()) {
            out.put(name, value);
        }
    }

    @Nullable
    private static String headerCaseInsensitive(Map<String, String> headers, String name) {
        String direct = headers.get(name);
        if (direct != null) return direct;
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private ResponseEntity<?> respondImmediately(VerificationResult.RespondImmediately r) {
        var resp = ResponseEntity.status(r.statusCode()).contentType(MediaType.parseMediaType(r.contentType()));
        for (Map.Entry<String, String> h : r.headers().entrySet()) {
            resp = resp.header(h.getKey(), h.getValue());
        }
        return resp.body(r.body());
    }

    public static Map<String, String> readHeaders(HttpServletRequest req) {
        Map<String, String> headers = new LinkedHashMap<>();
        var names = req.getHeaderNames();
        if (names == null) return Collections.unmodifiableMap(headers);
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, req.getHeader(name));
        }
        return Collections.unmodifiableMap(headers);
    }
}
