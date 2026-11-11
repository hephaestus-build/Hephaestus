package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbound webhook pipeline: verify → derive → store.
 *
 * <p>Verification can short-circuit with {@code RespondImmediately} (Slack
 * {@code url_verification}); on {@code Verified} the
 * pipeline derives the subject and delivery key via the per-kind {@link SubjectKeyDeriver}
 * and stores the delivery in the provider event inbox through {@link ProviderEventReceiver}. It answers 202 only
 * after the delivery is stored, and 503 when PostgreSQL cannot store it, so the provider retries.
 *
 * <p>Error responses carry only a coarse category ({@code "invalid"} / {@code "missing-signature"} /
 * {@code "stale-timestamp"}). The verifier's {@code Invalid.reason} — which distinguishes a missing secret from a
 * signature mismatch from a malformed header — is logged server-side only; echoing it would hand attacker probes a
 * side channel into the signing scheme.
 */
@Component
public class WebhookIngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestPipeline.class);

    static final String BUSY_REASON = "busy";
    static final String BUSY_RETRY_AFTER_SECONDS = "30";

    private final Map<IntegrationKind, WebhookSignatureVerifier> verifiersByKind;
    private final Map<IntegrationKind, SubjectKeyDeriver> deriversByKind;
    private final Map<IntegrationKind, WebhookPublishGate> publishGatesByKind;

    private final ProviderEventReceiver receiver;

    private final ObjectMapper objectMapper;
    private final WebhookInFlightBudget budget;
    private final MeterRegistry meterRegistry;

    public WebhookIngestPipeline(
            List<WebhookSignatureVerifier> verifiers,
            List<SubjectKeyDeriver> derivers,
            ProviderEventReceiver receiver,
            ObjectMapper objectMapper) {
        this(verifiers, derivers, receiver, objectMapper, List.of());
    }

    public WebhookIngestPipeline(
            List<WebhookSignatureVerifier> verifiers,
            List<SubjectKeyDeriver> derivers,
            ProviderEventReceiver receiver,
            ObjectMapper objectMapper,
            List<WebhookPublishGate> publishGates) {
        this(
                verifiers,
                derivers,
                receiver,
                objectMapper,
                publishGates,
                WebhookInFlightBudget.forHeap(Runtime.getRuntime().maxMemory()),
                new SimpleMeterRegistry());
    }

    @Autowired
    public WebhookIngestPipeline(
            List<WebhookSignatureVerifier> verifiers,
            List<SubjectKeyDeriver> derivers,
            ProviderEventReceiver receiver,
            ObjectMapper objectMapper,
            List<WebhookPublishGate> publishGates,
            WebhookInFlightBudget budget,
            MeterRegistry meterRegistry) {
        this.budget = budget;
        this.meterRegistry = meterRegistry;
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
        this.receiver = receiver;
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
        if (body.length <= WebhookInFlightBudget.SMALL_BODY_BYTES) {
            return publishHeld(kind, body, headers, route);
        }
        try (WebhookInFlightBudget.Holding holding = budget.forPublishing()) {
            if (!holding.add(body.length * WebhookInFlightBudget.PUBLISH_HEAP_PER_BODY_BYTE)) {
                String provider = kind.name().toLowerCase(Locale.ROOT);
                meterRegistry
                        .counter(IntegrationCoreMetrics.WEBHOOK_REJECTED, "provider", provider, "reason", BUSY_REASON)
                        .increment();
                return busy();
            }
            return publishHeld(kind, body, headers, route);
        }
    }

    private ResponseEntity<?> publishHeld(
            IntegrationKind kind, byte[] body, Map<String, String> headers, @Nullable Route route) {
        SubjectKeyDeriver deriver = deriversByKind.get(kind);
        if (route == null && deriver == null) {
            // Kind has a verifier but no derivation — surface as NOT_IMPLEMENTED so the
            // operator notices the gap rather than silently dropping into a default subject.
            log.warn("WebhookIngestPipeline: no SubjectKeyDeriver wired for kind={}", kind);
            return problem(HttpStatus.NOT_IMPLEMENTED, "no subject deriver for " + kind);
        }

        JsonNode payload;
        try {
            // Erasure reads the stored bytes as PostgreSQL UTF-8 text. Jackson's byte parser also accepts UTF-16/32.
            String json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            if (json.indexOf('\0') >= 0) return problem(HttpStatus.BAD_REQUEST, "invalid-json");
            payload = objectMapper.readTree(json);
            if (!postgresJsonText(payload)) return problem(HttpStatus.BAD_REQUEST, "invalid-json");
        } catch (Exception e) {
            return problem(HttpStatus.BAD_REQUEST, "invalid-json");
        }

        WebhookPublishGate gate = publishGatesByKind.get(kind);
        if (gate != null) {
            WebhookPublishGate.Decision decision;
            try {
                decision = gate.evaluate(payload, headers);
            } catch (DataAccessException e) {
                // The gate cannot read what it decides on: nothing is stored, and the provider retries.
                log.warn(
                        "Publish gate for kind={} unavailable: cause={}",
                        kind,
                        e.getClass().getName());
                return problem(HttpStatus.SERVICE_UNAVAILABLE, "gate-unavailable");
            }
            if (!decision.publish()) {
                log.info("Dropped {} webhook before it was stored: {}", kind, sanitizeForLog(decision.reason()));
                return ResponseEntity.accepted().body(Map.of("status", "dropped"));
            }
        }

        String subject = route != null
                ? route.subject().apply(payload)
                : Objects.requireNonNull(deriver).deriveSubject(payload, headers);
        String dedupId = route != null
                ? route.dedupId()
                : Objects.requireNonNull(deriver).deriveDedupKey(body, headers);

        Map<String, String> storedHeaders = new LinkedHashMap<>();
        passthroughHeader(storedHeaders, headers, "X-GitHub-Event");
        passthroughHeader(storedHeaders, headers, "X-GitHub-Delivery");
        passthroughHeader(storedHeaders, headers, "X-Gitlab-Event");
        passthroughHeader(storedHeaders, headers, "X-Gitlab-Webhook-UUID");
        if (route != null) {
            storedHeaders.putAll(route.headers());
        }

        String provider = kind.name().toLowerCase(Locale.ROOT);
        int stored;
        try {
            stored = receiver.receive(kind, subject, dedupId, storedHeaders, body);
        } catch (DataAccessException | TransactionException e) {
            meterRegistry
                    .counter(IntegrationCoreMetrics.WEBHOOK_PUBLISH, "stream", provider, "outcome", "failure")
                    .increment();
            log.atError()
                    .addKeyValue("event.name", "webhook.publish.failed")
                    .log(
                            "Webhook not stored for kind={} subject={}: cause={}",
                            kind,
                            sanitizeForLog(subject),
                            e.getClass().getName());
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "store-failed");
        }
        meterRegistry
                .counter(IntegrationCoreMetrics.WEBHOOK_PUBLISH, "stream", provider, "outcome", "success")
                .increment();
        log.info(
                "Stored {} webhook: subject={} dedupId={} events={}",
                kind,
                sanitizeForLog(subject),
                sanitizeForLog(dedupId),
                stored);
        return ResponseEntity.accepted().body(Map.of("status", "ok"));
    }

    private static boolean postgresJsonText(JsonNode node) {
        if (node.isString()) return postgresText(node.asString());
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (!postgresText(field.getKey()) || !postgresJsonText(field.getValue())) return false;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (!postgresJsonText(child)) return false;
            }
        }
        return true;
    }

    private static boolean postgresText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == 0 || Character.isLowSurrogate(value)) return false;
            if (Character.isHighSurrogate(value)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            }
        }
        return true;
    }

    static ResponseEntity<ProblemDetail> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, BUSY_RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, BUSY_REASON));
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
