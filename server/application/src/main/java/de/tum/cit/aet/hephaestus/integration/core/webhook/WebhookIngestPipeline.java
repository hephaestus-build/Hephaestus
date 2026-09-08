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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "no verifier wired for " + kind));
        }

        WebhookRequest request = new WebhookRequest(body, headers);
        VerificationResult result;
        try {
            result = verifier.verify(request);
        } catch (RuntimeException e) {
            log.warn("Verifier {} threw for kind={}: {}", verifier.getClass().getSimpleName(), kind, e.toString());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid"));
        }

        return switch (result) {
            case VerificationResult.Verified ignored -> publish(kind, body, headers);
            case VerificationResult.RespondImmediately r -> respondImmediately(r);
            case VerificationResult.StaleTimestamp s -> {
                log.debug("Webhook rejected for kind={}: stale timestamp drift={}s", kind, s.driftSeconds());
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "stale-timestamp"));
            }
            case VerificationResult.Invalid i -> {
                log.warn("Webhook rejected for kind={}: {}", kind, i.reason());
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid"));
            }
            case VerificationResult.MissingSignature ignored ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "missing-signature"));
        };
    }

    private ResponseEntity<?> publish(IntegrationKind kind, byte[] body, Map<String, String> headers) {
        if (jetStreamPublisher == null) {
            // No publisher bean — the webhook runtime role is disabled. Vendor will retry.
            // 503 is the right surface here: the verification succeeded, but the downstream
            // pipe is intentionally not wired on this pod.
            log.warn("WebhookIngestPipeline: verified {} webhook but no JetStreamPublisher bean — replying 503", kind);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "publisher not wired"));
        }
        SubjectKeyDeriver deriver = deriversByKind.get(kind);
        if (deriver == null) {
            // Kind has a verifier but no derivation — surface as NOT_IMPLEMENTED so the
            // operator notices the gap rather than silently dropping into a default subject.
            log.warn("WebhookIngestPipeline: no SubjectKeyDeriver wired for kind={}", kind);
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "no subject deriver for " + kind));
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid-json"));
        }

        WebhookPublishGate gate = publishGatesByKind.get(kind);
        if (gate != null) {
            WebhookPublishGate.Decision decision = gate.evaluate(payload, headers);
            if (!decision.publish()) {
                log.info("Dropped {} webhook before durable publish: {}", kind, sanitizeForLog(decision.reason()));
                return ResponseEntity.accepted().body(Map.of("status", "dropped"));
            }
        }

        String subject = deriver.deriveSubject(payload, headers);
        String dedupId = deriver.deriveDedupKey(body, headers);

        // Nats-Msg-Id also rides in JetStreamPublisher's PublishOptions; duplicating it on the
        // headers keeps the wire trace self-describing.
        Map<String, String> outboundHeaders = new LinkedHashMap<>();
        passthroughHeader(outboundHeaders, headers, "X-GitHub-Event");
        passthroughHeader(outboundHeaders, headers, "X-GitHub-Delivery");
        passthroughHeader(outboundHeaders, headers, "X-Gitlab-Event");
        passthroughHeader(outboundHeaders, headers, "X-Gitlab-Webhook-UUID");
        outboundHeaders.put(NATS_MSG_ID, dedupId);

        try {
            PublishRequest request = new PublishRequest(subject, dedupId, outboundHeaders, body);
            if (kind == IntegrationKind.SLACK) {
                jetStreamPublisher.publishFast(request, SLACK_PUBLISH_TIMEOUT);
            } else {
                jetStreamPublisher.publish(request);
            }
        } catch (JetStreamPublisher.PublishFailedException e) {
            log.error(
                    "WebhookIngestPipeline: publish failed for kind={} subject={}: {}",
                    kind,
                    sanitizeForLog(subject),
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "publish-failed"));
        }
        log.info(
                "Published {} webhook to NATS: subject={} dedupId={}",
                kind,
                sanitizeForLog(subject),
                sanitizeForLog(dedupId));
        return ResponseEntity.accepted().body(Map.of("status", "ok"));
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

    private static Map<String, String> readHeaders(HttpServletRequest req) {
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
