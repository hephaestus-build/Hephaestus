package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.StreamOutcome;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.UpstreamResult;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClientRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// The authenticated token chooses the model and upstream; callers cannot supply hosts, paths, or credentials.
@Service
@ConditionalOnWorkerRole
class LlmProxyService {

    private static final Logger log = LoggerFactory.getLogger(LlmProxyService.class);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(310);
    private static final String PROXY_PATH_PREFIX = "/internal/llm";

    /** The refusal of a model call after its review attempt's work deadline. */
    static final String WORK_TIME_OVER = "This review's work time has ended; it makes no more model calls.";

    private static final String AI_CHOICE_REFUSED = "Your AI choice no longer permits this model in this workspace.";

    /** Names which of the proxy's own rules refused a call, so an in-process caller need not read the refusal's words. */
    static final String REFUSAL_HEADER = "Hephaestus-Proxy-Refusal";

    /** The provider sent no byte for the idle bound of the call. */
    static final String UPSTREAM_STALLED = "upstream_stalled";

    /** The connection's breaker refuses calls after repeated failures. */
    static final String UPSTREAM_PAUSED = "upstream_paused";

    /** The provider answered with a status other than 2xx. */
    static final String UPSTREAM_STATUS = "upstream_status";

    /** The proxy answered the call with an error of its own, before or without the provider. */
    static final String GATEWAY_ERROR = "gateway_error";

    private final WebClient webClient;
    private final LlmModelResolver resolver;
    private final ObjectMapper objectMapper;
    private final ProxyStallErrors errors;
    private final ProxyAccounting accounting;
    private final ScanCallGuard scanCalls;
    private final ProxyRequestPolicy requestPolicy;
    private final Tracer tracer;
    private final ProxyRoutingService routingService;
    private final ProxyCallsInFlight callsInFlight;
    private final LlmConnectionHealth health;
    private final Bounds hephBounds;

    LlmProxyService(
            Tracer tracer,
            WebClient llmProxyWebClient,
            LlmModelResolver llmModelResolver,
            ObjectMapper objectMapper,
            ProxyAccounting accounting,
            ProxyRequestPolicy requestPolicy,
            ScanCallGuard scanCalls,
            ProxyRoutingService routingService,
            ProxyCallsInFlight callsInFlight,
            LlmConnectionHealth health,
            @Value("${hephaestus.mentor.turn.model-first-byte-timeout:PT5M}") Duration hephFirstByteTimeout,
            @Value("${hephaestus.mentor.turn.model-idle-timeout:PT2M}") Duration hephIdleTimeout) {
        this.tracer = tracer;
        this.webClient = llmProxyWebClient;
        this.resolver = llmModelResolver;
        this.objectMapper = objectMapper;
        this.errors = new ProxyStallErrors(objectMapper);
        this.accounting = accounting;
        this.requestPolicy = requestPolicy;
        this.scanCalls = scanCalls;
        this.routingService = routingService;
        this.callsInFlight = callsInFlight;
        this.health = health;
        this.hephBounds = new Bounds(hephFirstByteTimeout, hephIdleTimeout);
    }

    public @Nullable ResponseEntity<?> proxy(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte @Nullable [] body) {
        ProxyRouting routing = authenticatedRouting(request);
        ResponseEntity<String> rejected = validateSafeSurface(request);
        if (rejected != null) return rejected;
        LlmApiProtocol protocol = LlmApiProtocol.parse(routing.apiProtocol())
                .filter(parsed -> parsed.serves(ModelKind.CHAT))
                .orElse(null);
        if (routing.practiceScriptSlot() != null
                || protocol == null
                || !(PROXY_PATH_PREFIX + protocol.upstreamPath()).equals(request.getRequestURI())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatus(HttpStatus.NOT_FOUND));
        }
        if (body == null || body.length == 0) return ResponseEntity.badRequest().body("Request body is required");
        rejected = validateJsonObject(body);
        if (rejected != null) return rejected;
        return serve(routing, protocol, null, response, incomingHeaders, body);
    }

    /**
     * Serves one call of a practice script on {@code /internal/llm/practice-script/{slot}/{op}}. The op must
     * be the path of the slot's frozen protocol. The call must name its practice in
     * {@link JobTokenAuthenticationFilter#PRACTICE_SCRIPT_PRACTICE_HEADER}. The call is never streamed, so its
     * usage is always read from the response body.
     */
    public @Nullable ResponseEntity<?> proxyPracticeScript(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte @Nullable [] body) {
        ProxyRouting routing = authenticatedRouting(request);
        ResponseEntity<String> rejected = validateSafeSurface(request);
        if (rejected != null) return rejected;
        ModelKind slot = routing.practiceScriptSlot();
        LlmApiProtocol protocol = LlmApiProtocol.parse(routing.apiProtocol())
                .filter(parsed -> slot != null && parsed.serves(slot))
                .orElse(null);
        if (slot == null || protocol == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatus(HttpStatus.NOT_FOUND));
        }
        String slotPath =
                JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PATH + slot.name().toLowerCase(Locale.ROOT);
        if (!(slotPath + protocol.upstreamPath()).equals(request.getRequestURI())) {
            return ResponseEntity.badRequest().body("This model takes calls on " + slotPath + protocol.upstreamPath());
        }
        String practice = incomingHeaders.getFirst(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PRACTICE_HEADER);
        if (practice == null || !SandboxLayout.PRACTICE_SLUG.matcher(practice).matches()) {
            accounting.recordUnattributedPracticeScript();
            return ResponseEntity.badRequest()
                    .body("A practice script call must name its practice in "
                            + JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PRACTICE_HEADER);
        }
        if (body == null || body.length == 0) return ResponseEntity.badRequest().body("Request body is required");
        rejected = validateJsonObject(body);
        if (rejected != null) return rejected;
        JsonNode call = objectMapper.readTree(body);
        if (call.path("stream").asBoolean(false)) {
            return ResponseEntity.badRequest().body("Practice script calls cannot stream");
        }
        String tooManyEntries = tooManyEntries(call, protocol);
        if (tooManyEntries != null)
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(tooManyEntries);
        return serve(
                routing,
                protocol,
                new PracticeScriptCall(slot, routing.practiceScriptTier(), practice),
                response,
                incomingHeaders,
                body);
    }

    // MODEL_SLOT_CAPS in docker/agents/practice-scripts/lib/core/contract.ts holds the same caps, so the runner refuses
    // first.
    static final int MAX_DECISION_QUESTIONS = 64;
    static final int MAX_EMBEDDING_INPUTS = 64;
    static final int MAX_RERANK_DOCUMENTS = 256;

    private static @Nullable String tooManyEntries(JsonNode call, LlmApiProtocol protocol) {
        return switch (protocol) {
            case OPENAI_DECISIONS -> tooManyEntries(call, "questions", MAX_DECISION_QUESTIONS);
            case OPENAI_EMBEDDINGS -> tooManyEntries(call, "input", MAX_EMBEDDING_INPUTS);
            case COHERE_RERANK -> tooManyEntries(call, "documents", MAX_RERANK_DOCUMENTS);
            case OPENAI_COMPLETIONS, OPENAI_RESPONSES -> null;
        };
    }

    private static @Nullable String tooManyEntries(JsonNode call, String field, int max) {
        JsonNode entries = call.get(field);
        if (entries == null || !entries.isArray() || entries.size() <= max) return null;
        return "'" + field + "' must hold at most " + max + " entries";
    }

    /**
     * Serves one call on a routing: the AI-choice, budget, connection, deadline and usage rules. A null practiceScript
     * denotes a review or Heph call. A null response serves a worker step in-process: the call must not stream, and a
     * provider that answers with a stream anyway is refused.
     */
    @Nullable
    ResponseEntity<?> serve(
            ProxyRouting routing,
            LlmApiProtocol protocol,
            @Nullable PracticeScriptCall practiceScript,
            @Nullable HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte[] body) {
        accounting.recordGatewayRequest(body.length);
        try (ProxyCallsInFlight.Call inFlight = callsInFlight.begin(routing.attempt())) {
            return serve(routing, protocol, practiceScript, response, incomingHeaders, body, inFlight);
        }
    }

    @SuppressWarnings("try") // The scope installs the current span and restores it on close.
    private @Nullable ResponseEntity<?> serve(
            ProxyRouting routing,
            LlmApiProtocol protocol,
            @Nullable PracticeScriptCall practiceScript,
            @Nullable HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte[] body,
            ProxyCallsInFlight.Call inFlight) {
        MDC.put("proxy.principal", routing.principalDescription());
        MDC.put("proxy.apiProtocol", routing.apiProtocol());
        Timer.Sample timer = accounting.startTimer();
        String operation = operationName(protocol);
        Span span = tracer.spanBuilder()
                .name("gen_ai." + operation)
                .kind(Span.Kind.CLIENT)
                .tag("gen_ai.operation.name", operation)
                .tag("hephaestus.pi_request.sha256", ProvenanceDigest.sha256Hex(body))
                .start();
        if (routing.sourceId() != null)
            span.tag("hephaestus.job.id", routing.sourceId().toString());
        if (routing.workspaceId() != null) span.tag("hephaestus.workspace.id", routing.workspaceId());
        long started = System.nanoTime();
        String ended = "error";
        try (var ignored = tracer.withSpan(span)) {
            var result = forward(routing, protocol, practiceScript, response, incomingHeaders, body, span, inFlight);
            int status = result == null
                    ? Objects.requireNonNull(response).getStatus()
                    : result.getStatusCode().value();
            if (status >= 400 && inFlight.failure() == null) {
                // The proxy answered itself: a refusal names its rule, any other answer is the gateway's own.
                String refused = result == null ? null : result.getHeaders().getFirst(REFUSAL_HEADER);
                inFlight.failed(new MentorTurn.FailedCall(
                        refused == null ? GATEWAY_ERROR : "refused_" + refused.toLowerCase(Locale.ROOT), status));
            }
            ended = Integer.toString(status);
            span.tag("http.response.status_code", status);
            if (status >= 400) recordSpanError(span, Integer.toString(status));
            return result;
        } catch (RuntimeException e) {
            ended = e.getClass().getSimpleName();
            recordSpanError(span, e.getClass().getSimpleName());
            throw e;
        } finally {
            // One line per model call of a Heph turn, as for its tool calls (HephToolCalls): a turn that keeps
            // calling shows as a run of them. A retry of a failed call is its own line, and a failed call a warning.
            if (routing.attempt().sourceType() == UsageSourceType.MENTOR_TURN) {
                MentorTurn.FailedCall failure = inFlight.failure();
                LoggingEventBuilder line = log.atLevel(failure == null ? Level.INFO : Level.WARN)
                        .addKeyValue("event.name", "heph.turn.model_call")
                        .addKeyValue("turn.id", routing.attempt().sourceId())
                        .addKeyValue("turn.attempt", routing.attempt().number())
                        .addKeyValue("outcome", ended)
                        .addKeyValue(
                                "duration.ms",
                                Duration.ofNanos(System.nanoTime() - started).toMillis());
                if (failure != null) {
                    line = line.addKeyValue("failure", failure.kind());
                    if (failure.status() != null) line = line.addKeyValue("failure.status", failure.status());
                }
                line.log(failure == null ? "Heph model call" : "Heph model call failed");
            }
            span.end();
            accounting.stopTimer(timer, routing.apiProtocol());
            MDC.remove("proxy.principal");
            MDC.remove("proxy.apiProtocol");
        }
    }

    private static String operationName(LlmApiProtocol protocol) {
        return switch (protocol.kind()) {
            case CHAT -> "chat";
            case DECISION -> "decision";
            case EMBEDDING -> "embeddings";
            case RERANKING -> "rerank";
        };
    }

    private @Nullable ResponseEntity<?> forward(
            ProxyRouting routing,
            LlmApiProtocol protocol,
            @Nullable PracticeScriptCall practiceScript,
            @Nullable HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte[] body,
            Span span,
            ProxyCallsInFlight.Call call) {
        if (workTimeOver(routing))
            return refusal(HttpStatus.FORBIDDEN, ReviewModelGateway.Refusal.WORK_TIME, WORK_TIME_OVER);
        // A scan call may wait for its attempt's previous call, at most until the work deadline.
        var lease = scanCalls.acquire(routing, body);
        try {
            if (workTimeOver(routing))
                return refusal(HttpStatus.FORBIDDEN, ReviewModelGateway.Refusal.WORK_TIME, WORK_TIME_OVER);
            return forwardWithinScanBudget(
                    routing, protocol, practiceScript, response, incomingHeaders, body, span, call);
        } finally {
            lease.ifPresent(ScanCallGuard.Lease::close);
        }
    }

    private @Nullable ResponseEntity<?> forwardWithinScanBudget(
            ProxyRouting routing,
            LlmApiProtocol protocol,
            @Nullable PracticeScriptCall practiceScript,
            @Nullable HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte[] body,
            Span span,
            ProxyCallsInFlight.Call call) {
        ProxyRouting.BilledAttempt attempt = routing.attempt();
        if (call.isCancelled()) {
            return refusal(
                    HttpStatus.CONFLICT, ReviewModelGateway.Refusal.ATTEMPT_ENDED, "This turn attempt has ended");
        }

        if (!requestPolicy.allows(routing)) {
            return refusal(HttpStatus.FORBIDDEN, ReviewModelGateway.Refusal.AI_CHOICE, AI_CHOICE_REFUSED);
        }

        // A practice script call is refused with 402, which no rate limit answers, so its runner can tell a
        // spent budget from the gateway's or the provider's 429. The AI SDK also never retries a 402.
        HttpStatus spendingRefusal =
                practiceScript == null ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.PAYMENT_REQUIRED;
        // Before any credential is resolved or the network touched. Never interrupts a live stream.
        if (accounting.refuseForBudget(routing)) {
            return refusal(
                    spendingRefusal,
                    ReviewModelGateway.Refusal.BUDGET,
                    budgetReachedMessage(routing.connectionScope()));
        }
        if (practiceScript != null && accounting.refuseForDirectModelCap(routing, attempt)) {
            return refusal(spendingRefusal, ReviewModelGateway.Refusal.BUDGET, "Practice script budget spent");
        }

        LlmModelResolver.ProxyCredential credential =
                resolver.resolveProxyCredential(new LlmModelResolver.ConnectionRef(
                        routing.connectionScope(), routing.connectionId(), routing.modelId(), routing.workspaceId()));
        if (credential == null) {
            accounting.recordError(routing.apiProtocol());
            return ResponseEntity.status(502).body("The configured model is not available");
        }
        if (!routing.apiProtocol().equals(credential.apiProtocol())) {
            accounting.recordError(routing.apiProtocol());
            return ResponseEntity.status(502).body("The configured model protocol changed");
        }

        try {
            requestPolicy.validateTarget(credential.baseUrl());
        } catch (IllegalArgumentException e) {
            accounting.recordError(routing.apiProtocol());
            return ResponseEntity.status(502).body("Upstream target not permitted");
        }

        boolean responsesProtocol = protocol == LlmApiProtocol.OPENAI_RESPONSES;
        PreparedBody prepared = prepareBody(body, credential.upstreamModelId(), !responsesProtocol);
        if (prepared == null) return ResponseEntity.badRequest().body("Request body must be a JSON object");

        URI upstreamUri;
        try {
            upstreamUri = buildUpstreamUri(credential.baseUrl(), protocol);
        } catch (IllegalArgumentException e) {
            accounting.recordError(routing.apiProtocol());
            return ResponseEntity.status(502).body("Invalid upstream configuration");
        }

        LlmConnectionHealth.Permit permit = health.acquire(routing.connectionScope(), routing.connectionId());
        if (permit == null) {
            accounting.recordError(routing.apiProtocol());
            call.failed(new MentorTurn.FailedCall(UPSTREAM_PAUSED, null));
            return errors.providerError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    UPSTREAM_PAUSED,
                    "the model provider failed repeatedly, so Hephaestus waits up to "
                            + LlmConnectionHealth.OPEN_FOR.toSeconds() + " seconds before it calls it again");
        }
        span.tag("gen_ai.request.model", credential.upstreamModelId());
        Exchange exchange = new Exchange(
                upstreamUri,
                buildUpstreamHeaders(incomingHeaders, credential),
                attempt.sourceType() == UsageSourceType.MENTOR_TURN ? hephBounds : null,
                call.cancelled());
        ProxyStreamUsageTap tap = new ProxyStreamUsageTap(objectMapper, responsesProtocol);
        // Both requests below share the attempt's one deadline; the second one does not get a new window.
        Instant deadline = routing.workDeadline();
        boolean reviewStream = practiceScript == null && requestsStream(prepared.body());
        UpstreamResult upstream;
        try {
            if (workTimeOver(routing)) {
                permit.released();
                return refusal(HttpStatus.FORBIDDEN, ReviewModelGateway.Refusal.WORK_TIME, WORK_TIME_OVER);
            }
            upstream = callUpstream(exchange, prepared.body(), span, response, tap, deadline, reviewStream);
            if (rejectedOurUsageRequest(upstream, prepared)) {
                // Asking for usage is OUR addition, so refusing it must cost the caller nothing.
                log.info(
                        "Upstream rejected stream_options.include_usage for principal {}; retrying without it — "
                                + "this call's tokens will not be metered",
                        routing.principalDescription());
                accounting.recordStreamUsageUnsupported(routing.apiProtocol());
                if (!requestPolicy.allows(routing)) {
                    permit.released();
                    return refusal(HttpStatus.FORBIDDEN, ReviewModelGateway.Refusal.AI_CHOICE, AI_CHOICE_REFUSED);
                }
                if (workTimeOver(routing)) {
                    permit.released();
                    return refusal(HttpStatus.FORBIDDEN, ReviewModelGateway.Refusal.WORK_TIME, WORK_TIME_OVER);
                }
                upstream = callUpstream(
                        exchange, prepared.withoutUsageRequestOrBody(), span, response, tap, deadline, reviewStream);
            }
        } catch (Exception e) {
            // Only a failure before a stream's head reaches here, so nothing has been written to the client.
            permit.failed(e);
            if (practiceScript != null) accounting.recordUsage(attempt, practiceScript, null);
            Bounds bounds = exchange.bounds();
            if (bounds != null && ProxyStreamingUtils.readTimedOut(e)) {
                accounting.recordError(routing.apiProtocol());
                accounting.recordUpstreamStalled(routing.apiProtocol());
                String stall = "the model provider sent no first byte within "
                        + bounds.firstByte().toSeconds() + " seconds";
                warnCall(routing, "LLM upstream stalled for principal {}: {}", routing.principalDescription(), stall);
                call.failed(new MentorTurn.FailedCall(UPSTREAM_STALLED, null));
                return errors.providerError(HttpStatus.GATEWAY_TIMEOUT, UPSTREAM_STALLED, stall);
            }
            // An anonymous class has no simple name.
            String failure = e.getClass().getSimpleName();
            call.failed(
                    new MentorTurn.FailedCall(failure.isEmpty() ? e.getClass().getName() : failure, null));
            if (e instanceof WebClientRequestException) {
                warnCall(
                        routing,
                        "LLM upstream unreachable for principal {}: reason={}",
                        routing.principalDescription(),
                        e.getClass().getSimpleName());
                accounting.recordError(routing.apiProtocol());
                return ResponseEntity.status(502).body("Upstream provider unreachable");
            }
            warnCall(
                    routing,
                    "LLM upstream request failed for principal {}: reason={}",
                    routing.principalDescription(),
                    e.getClass().getSimpleName());
            accounting.recordError(routing.apiProtocol());
            // The work deadline is answered as such; any other failure as before.
            if (workTimeOver(routing))
                return refusal(HttpStatus.FORBIDDEN, ReviewModelGateway.Refusal.WORK_TIME, WORK_TIME_OVER);
            return ResponseEntity.status(502).body("Upstream request failed");
        }

        if (upstream == null) {
            // The turn's end cancelled the call before the provider answered.
            permit.released();
            if (practiceScript != null) accounting.recordUsage(attempt, practiceScript, null);
            accounting.recordError(routing.apiProtocol());
            return ResponseEntity.status(502).body("Upstream provider unavailable");
        }
        if (call.isCancelled()) {
            // The turn ended without this call; the provider's state is unknown.
            permit.released();
        } else {
            ProxyStallErrors.judge(upstream, permit);
        }
        Bounds bounds = exchange.bounds();
        if (bounds != null && upstream.streamOutcome() == StreamOutcome.UPSTREAM_IDLE) {
            accounting.recordUpstreamStalled(routing.apiProtocol());
            String stall = "the model provider's stream sent nothing for "
                    + bounds.idle().toSeconds() + " seconds";
            warnCall(routing, "LLM upstream stalled for principal {}: {}", routing.principalDescription(), stall);
            if (response != null) errors.writeStallFrame(response, stall);
        }
        var outcome = upstream.streamOutcome();
        if (outcome != null)
            return settleStream(routing, attempt, practiceScript, span, upstream.status(), outcome, tap, call);
        boolean served = upstream.status() >= 200 && upstream.status() < 300;
        if (!served) {
            call.failed(new MentorTurn.FailedCall(UPSTREAM_STATUS, upstream.status()));
            logUnserved(routing, upstream.status());
            accounting.recordError(routing.apiProtocol());
        }
        // Attributed now, not at the run's terminal write, so an execution that dies still bills.
        if (upstream.body() != null && served) {
            tagUsage(span, accounting.recordUsage(attempt, practiceScript, protocol, upstream.body()));
        }
        return ResponseEntity.status(upstream.status())
                .headers(upstream.headers())
                .body(upstream.body());
    }

    /**
     * Settles a call whose stream wrote the response: one outcome, and the usage the stream reported up to its end.
     * Its status and bytes are the answer, so no body of our own follows.
     */
    private @Nullable ResponseEntity<?> settleStream(
            ProxyRouting routing,
            ProxyRouting.BilledAttempt attempt,
            @Nullable PracticeScriptCall practiceScript,
            Span span,
            int status,
            ProxyStreamingUtils.StreamOutcome outcome,
            ProxyStreamUsageTap tap,
            ProxyCallsInFlight.Call call) {
        boolean served = status >= 200 && status < 300;
        if (!served) {
            call.failed(new MentorTurn.FailedCall(UPSTREAM_STATUS, status));
            logUnserved(routing, status);
        }
        span.tag("hephaestus.stream.outcome", outcome.name());
        if (outcome != ProxyStreamingUtils.StreamOutcome.COMPLETED) {
            recordSpanError(span, outcome.name());
            accounting.recordError(routing.apiProtocol());
            // Names the caller beside the outcome; the shared stream code knows neither.
            if (outcome != ProxyStreamingUtils.StreamOutcome.CLIENT_DISCONNECTED) {
                warnCall(
                        routing,
                        "LLM stream for principal {} ended with outcome={}",
                        routing.principalDescription(),
                        outcome);
                call.failed(new MentorTurn.FailedCall(outcome.name().toLowerCase(Locale.ROOT), status));
            }
        }
        if (served) {
            if (tap.hasMalformedUsage()) {
                accounting.recordMalformedUsage(attempt);
            } else {
                accounting.recordUsage(attempt, practiceScript, tap.observed());
                tagUsage(span, tap.observed());
            }
        }
        return null;
    }

    private void logUnserved(ProxyRouting routing, int status) {
        // The sandbox only sees "error" from its model client; the trace carries the status on the
        // span and this is its log-side counterpart, for an operator reading logs rather than
        // traces. Status and principal only: the body can quote the request.
        warnCall(
                routing,
                "LLM upstream answered a call for principal {} with status={}",
                routing.principalDescription(),
                status);
    }

    /** Warns about a failed call. A Heph turn's call logs one line with its turn instead, at its end in serve(). */
    private static void warnCall(ProxyRouting routing, String format, Object... arguments) {
        if (routing.attempt().sourceType() != UsageSourceType.MENTOR_TURN) log.warn(format, arguments);
    }

    /**
     * How long a Heph turn's provider may send nothing. A provider can queue a request before it sends its response
     * headers, so the first byte gets its own, longer bound. The turn's deadline bounds both: its stop cancels the call.
     *
     * @param firstByte until the response headers
     * @param idle between two reads of the response body; an SSE comment counts
     */
    record Bounds(Duration firstByte, Duration idle) {}

    /**
     * One upstream call.
     *
     * @param bounds the silence the call is cut after; {@code null} for the client's own read bound, whose timeout is a
     *     plain upstream failure
     * @param cancelled completes when the attempt's end cancels the call
     */
    private record Exchange(
            URI uri, HttpHeaders headers, @Nullable Bounds bounds, Mono<Void> cancelled) {}

    /**
     * One upstream exchange, run on this request thread, which alone writes the servlet response. Without a work
     * deadline (a mentor turn) it keeps the proxy's own limits: the whole exchange, and a stream's body from its start.
     * With one, the whole exchange — waiting for the head, the body, and a stream — ends by the deadline; a review's
     * stream may use all of the attempt's remaining time, while every other call also keeps the proxy's own ceiling.
     * A Heph turn's call also ends on the turn's {@code bounds} and when the turn ends.
     *
     * @return {@code null} when the attempt's end cancelled the call before the provider answered
     */
    private @Nullable UpstreamResult callUpstream(
            Exchange exchange,
            byte[] outgoingBody,
            Span span,
            @Nullable HttpServletResponse response,
            ProxyStreamUsageTap tap,
            @Nullable Instant deadline,
            boolean reviewStream) {
        Duration limit = exchangeWait(deadline, reviewStream);
        Duration bodyLimit = deadline == null ? ProxyStreamingUtils.DEFAULT_SSE_TIMEOUT : null;
        span.event("upstream.request");
        Bounds bounds = exchange.bounds();
        var request = webClient.method(HttpMethod.POST).uri(exchange.uri()).headers(headers -> {
            headers.clear();
            headers.addAll(exchange.headers());
        });
        if (bounds != null) {
            // Reactor Netty's response timeout bounds each read until the response is complete.
            request = request.httpRequest(
                    client -> client.<HttpClientRequest>getNativeRequest().responseTimeout(bounds.firstByte()));
        }
        UnaryOperator<Flux<DataBuffer>> body = bounds == null
                ? stream -> stream.takeUntilOther(exchange.cancelled())
                // Each data buffer is a read, so a keep-alive comment counts as a sign of life.
                : stream ->
                        ProxyStreamingUtils.endingWhenIdle(stream.takeUntilOther(exchange.cancelled()), bounds.idle());
        AtomicBoolean answered = new AtomicBoolean();
        AtomicBoolean cancelledFirst = new AtomicBoolean();
        var parts = request.bodyValue(outgoingBody)
                .exchangeToFlux(upstream -> {
                    answered.set(true);
                    return ProxyStreamingUtils.parts(upstream, bodyLimit, body);
                })
                // Before the response, a cancel drops the exchange; after it, the body stops and the stream ends.
                .takeUntilOther(exchange.cancelled().then(Mono.defer(() -> {
                    if (answered.get()) return Mono.never();
                    cancelledFirst.set(true);
                    return Mono.just(Boolean.TRUE);
                })));
        try {
            return ProxyStreamingUtils.forward(parts, limit, response, tap);
        } catch (IllegalStateException noHead) {
            if (cancelledFirst.get()) return null;
            throw noHead;
        }
    }

    /**
     * How long one exchange may wait. A mentor turn keeps the proxy's own ceiling. A review's stream gets the
     * attempt's remaining work time, however long; every other call of the attempt the shorter of the two.
     */
    static Duration exchangeWait(@Nullable Instant deadline, boolean reviewStream) {
        Duration ceiling = BLOCK_TIMEOUT.plus(ProxyStreamingUtils.DEFAULT_SSE_TIMEOUT);
        if (deadline == null) return ceiling;
        Duration remaining = ProxyStreamingUtils.remainingUntil(deadline);
        return reviewStream || remaining.compareTo(ceiling) < 0 ? remaining : ceiling;
    }

    private boolean requestsStream(byte[] body) {
        try {
            return objectMapper.readTree(body).path("stream").asBoolean(false);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Whether the job attempt's work time is over. A mentor turn has no work deadline. */
    private static boolean workTimeOver(ProxyRouting routing) {
        Instant deadline = routing.workDeadline();
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    // Not 429 or 5xx, so neither the runner nor a model client asks again; the words avoid what retry classifiers read.
    private static ResponseEntity<String> refusal(HttpStatus status, ReviewModelGateway.Refusal rule, String message) {
        return ResponseEntity.status(status).header(REFUSAL_HEADER, rule.name()).body(message);
    }

    /** Narrow on purpose: a blanket retry on 4xx would double every bad request the runner makes. */
    private static boolean rejectedOurUsageRequest(@Nullable UpstreamResult upstream, PreparedBody prepared) {
        if (upstream == null || prepared.withoutUsageRequest() == null) return false;
        if (upstream.status() != 400 && upstream.status() != 422) return false;
        byte[] body = upstream.body();
        return body != null && new String(body, StandardCharsets.UTF_8).contains("stream_options");
    }

    private static @Nullable ResponseEntity<String> validateSafeSurface(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()))
            return ResponseEntity.status(405).body("Method not allowed");
        if (request.getQueryString() != null)
            return ResponseEntity.badRequest().body("Query parameters are not allowed");
        return null;
    }

    private @Nullable ResponseEntity<String> validateJsonObject(byte[] body) {
        try {
            if (!objectMapper.readTree(body).isObject()) {
                return ResponseEntity.badRequest().body("Request body must be a JSON object");
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Request body must be valid JSON");
        }
        return null;
    }

    /**
     * @param withoutUsageRequest the body as the caller sent it; {@code null} when we added nothing, so
     *     a rejection is the caller's own and there is nothing to retry
     */
    @SuppressWarnings("ArrayRecordComponent") // raw request bytes sent upstream; never compared, hashed or printed
    record PreparedBody(byte[] body, byte @Nullable [] withoutUsageRequest) {
        byte[] withoutUsageRequestOrBody() {
            return withoutUsageRequest != null ? withoutUsageRequest : body;
        }
    }

    /**
     * Lock the model, strip what the sandbox may not ask for, and — on a streaming chat-completions
     * request — ask the provider to report usage.
     *
     * @param includeStreamingUsage true for chat-completions, whose streams report no usage unless the
     *     request carries {@code stream_options.include_usage}; the responses API reports it regardless
     * @return {@code null} when the body is not a JSON object or asks for a capability the proxy
     *     refuses to forward
     */
    @Nullable
    PreparedBody prepareBody(byte[] body, String upstreamModelId, boolean includeStreamingUsage) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode tree = objectMapper.readTree(body);
            if (!tree.isObject()) return null;
            ObjectNode object = (ObjectNode) tree;
            if (usesProviderHostedTool(object.get("tools"))
                    || object.has("web_search_options")
                    || object.has("audio")
                    || !isTextOnlyModality(object.get("modalities"))) return null;
            object.put("model", upstreamModelId);
            object.remove("service_tier");
            if (!includeStreamingUsage || !object.path("stream").asBoolean(false)) {
                return new PreparedBody(objectMapper.writeValueAsBytes(object), null);
            }
            byte[] asSent = objectMapper.writeValueAsBytes(object);
            JsonNode existing = object.get("stream_options");
            ObjectNode options = existing != null && existing.isObject()
                    ? (ObjectNode) existing
                    : object.putObject("stream_options");
            options.put("include_usage", true);
            byte[] withUsage = objectMapper.writeValueAsBytes(object);
            boolean weAddedTheFlag = !Arrays.equals(asSent, withUsage);
            return new PreparedBody(withUsage, weAddedTheFlag ? asSent : null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean usesProviderHostedTool(JsonNode tools) {
        if (tools == null) return false;
        if (!tools.isArray()) return true;
        for (JsonNode tool : tools) {
            String type = tool.path("type").asString("");
            if (!"function".equals(type) && !"custom".equals(type)) return true;
        }
        return false;
    }

    private static boolean isTextOnlyModality(JsonNode modalities) {
        return (modalities == null
                || (modalities.isArray()
                        && modalities.size() == 1
                        && "text".equals(modalities.get(0).asString())));
    }

    HttpHeaders buildUpstreamHeaders(HttpHeaders incomingHeaders, LlmModelResolver.ProxyCredential credential) {
        HttpHeaders outgoing = new HttpHeaders();
        outgoing.setContentType(MediaType.APPLICATION_JSON);
        if (incomingHeaders.getFirst(HttpHeaders.ACCEPT) != null) {
            outgoing.set(HttpHeaders.ACCEPT, incomingHeaders.getFirst(HttpHeaders.ACCEPT));
        }
        outgoing.set(HttpHeaders.ACCEPT_ENCODING, "identity");

        if (credential.apiKey() != null && !credential.apiKey().isBlank()) {
            if (credential.authMode() == LlmAuthMode.API_KEY) {
                outgoing.set("api-key", credential.apiKey());
            } else {
                outgoing.setBearerAuth(credential.apiKey());
            }
        }
        return outgoing;
    }

    static URI buildUpstreamUri(String baseUrl, LlmApiProtocol protocol) {
        return URI.create(baseUrl.strip().replaceAll("/+$", "") + protocol.upstreamPath());
    }

    private ProxyRouting authenticatedRouting(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JobTokenAuthentication tokenAuthentication) {
            return routingService.resolve(tokenAuthentication.getPrincipal(), request);
        }
        throw new IllegalStateException("Expected JobTokenAuthentication on security context");
    }

    /** Names WHICH purse stopped the call, because the two have different remedies. */
    private static String budgetReachedMessage(@Nullable UsageFundingSource fundingSource) {
        return fundingSource == UsageFundingSource.WORKSPACE
                ? "Own-provider budget reached. Paused until an admin raises the cap or the month rolls over."
                : "Shared-model budget reached. Paused until an admin raises the budget or the month rolls over.";
    }

    // OTel GenAI input includes cache tokens; search units have no standard token attribute.
    private static void tagUsage(Span span, @Nullable ProxyTokenUsage usage) {
        if (usage == null) return;
        if (usage.searchUnits() > 0) {
            span.tag("hephaestus.usage.search_units", usage.searchUnits());
            if (usage.promptTokens() == 0) return;
        }
        span.tag("gen_ai.usage.input_tokens", usage.promptTokens());
        span.tag("gen_ai.usage.output_tokens", usage.outputTokens());
    }

    private static void recordSpanError(Span span, String errorType) {
        // The original exception may contain provider content or credentials. Only a bounded failure
        // classification is exported; Micrometer's error API also sets the underlying OTLP status.
        span.error(new IllegalStateException(errorType));
        span.tag("error.type", errorType);
    }
}
