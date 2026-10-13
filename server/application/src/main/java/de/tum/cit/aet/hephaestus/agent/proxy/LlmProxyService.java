package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.StreamOutcome;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.UpstreamResult;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

/**
 * Credential-injecting proxy for the two OpenAI-compatible API surfaces used by agent sandboxes.
 * The authenticated token chooses a catalog model; callers cannot choose an upstream host, path,
 * protocol, credential header, or model id.
 */
@Service
@ConditionalOnWorkerRole
class LlmProxyService {

    private static final Logger log = LoggerFactory.getLogger(LlmProxyService.class);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(310);
    private static final String COMPLETIONS_PROTOCOL = "openai-completions";
    private static final String RESPONSES_PROTOCOL = "openai-responses";
    private static final String COMPLETIONS_PROXY_PATH = "/internal/llm/chat/completions";
    private static final String RESPONSES_PROXY_PATH = "/internal/llm/responses";

    /** The provider sent no byte for the idle bound of the call. */
    static final String UPSTREAM_STALLED = "upstream_stalled";

    /** The connection's breaker refuses calls after repeated failures. */
    static final String UPSTREAM_PAUSED = "upstream_paused";

    private final WebClient webClient;
    private final LlmModelResolver resolver;
    private final ObjectMapper objectMapper;
    private final ProxyAccounting accounting;
    private final ProxyRequestPolicy requestPolicy;
    private final ProxyCallsInFlight callsInFlight;
    private final LlmConnectionHealth health;
    private final Bounds hephBounds;
    private final Tracer tracer;

    LlmProxyService(
            Tracer tracer,
            WebClient llmProxyWebClient,
            LlmModelResolver llmModelResolver,
            ObjectMapper objectMapper,
            ProxyAccounting accounting,
            ProxyRequestPolicy requestPolicy,
            ProxyCallsInFlight callsInFlight,
            LlmConnectionHealth health,
            @Value("${hephaestus.mentor.turn.model-first-byte-timeout:PT5M}") Duration hephFirstByteTimeout,
            @Value("${hephaestus.mentor.turn.model-idle-timeout:PT2M}") Duration hephIdleTimeout) {
        this.tracer = tracer;
        this.webClient = llmProxyWebClient;
        this.resolver = llmModelResolver;
        this.objectMapper = objectMapper;
        this.accounting = accounting;
        this.requestPolicy = requestPolicy;
        this.callsInFlight = callsInFlight;
        this.health = health;
        this.hephBounds = new Bounds(hephFirstByteTimeout, hephIdleTimeout);
    }

    @SuppressWarnings("try") // The scope installs the current span and restores it on close.
    public @Nullable ResponseEntity<?> proxy(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte @Nullable [] body) {
        ProxyRouting routing = authenticatedRouting();
        ResponseEntity<String> rejected = validateSafeSurface(request, routing);
        if (rejected != null) return rejected;
        if (body == null || body.length == 0) return ResponseEntity.badRequest().body("Request body is required");
        rejected = validateJsonObject(body);
        if (rejected != null) return rejected;
        accounting.recordGatewayRequest(body.length);

        MDC.put("proxy.principal", routing.principalDescription());
        MDC.put("proxy.apiProtocol", routing.apiProtocol());
        Timer.Sample timer = accounting.startTimer();
        Span span = tracer.spanBuilder()
                .name("gen_ai.chat")
                .kind(Span.Kind.CLIENT)
                .tag("gen_ai.operation.name", "chat")
                .tag("hephaestus.pi_request.sha256", ProvenanceDigest.sha256Hex(body))
                .start();
        span.tag("hephaestus.job.id", routing.sourceId().toString());
        if (routing.workspaceId() != null) span.tag("hephaestus.workspace.id", routing.workspaceId());
        try (var ignored = tracer.withSpan(span);
                var inFlight = callsInFlight.begin(routing.attempt())) {
            var result = forward(routing, response, incomingHeaders, body, span, inFlight);
            int status = result == null
                    ? response.getStatus()
                    : result.getStatusCode().value();
            span.tag("http.response.status_code", status);
            if (status >= 400) recordSpanError(span, Integer.toString(status));
            return result;
        } catch (RuntimeException e) {
            recordSpanError(span, e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
            accounting.stopTimer(timer, routing.apiProtocol());
            MDC.remove("proxy.principal");
            MDC.remove("proxy.apiProtocol");
        }
    }

    private @Nullable ResponseEntity<?> forward(
            ProxyRouting routing,
            HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte[] body,
            Span span,
            ProxyCallsInFlight.Call call) {
        if (call.isCancelled()) {
            return ResponseEntity.status(409).body("This turn attempt has ended");
        }
        if (!requestPolicy.allows(routing)) {
            return ResponseEntity.status(403).body("Your AI choice no longer permits this model in this workspace.");
        }

        // Before any credential is resolved or the network touched. Never interrupts a live stream.
        if (accounting.refuseForBudget(routing)) {
            return ResponseEntity.status(429).body(budgetReachedMessage(routing.connectionScope()));
        }

        LlmModelResolver.ProxyCredential credential =
                resolver.resolveProxyCredential(new LlmModelResolver.ConnectionRef(
                        routing.connectionScope(), routing.connectionId(), routing.modelId(), routing.workspaceId()));
        if (credential == null) {
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("The configured model is not available");
        }
        if (!routing.apiProtocol().equals(credential.apiProtocol())) {
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("The configured model protocol changed");
        }

        try {
            requestPolicy.validateTarget(credential.baseUrl());
        } catch (IllegalArgumentException e) {
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("Upstream target not permitted");
        }

        boolean responsesProtocol = RESPONSES_PROTOCOL.equals(routing.apiProtocol());
        PreparedBody prepared = prepareBody(body, credential.upstreamModelId(), !responsesProtocol);
        if (prepared == null) return ResponseEntity.badRequest().body("Request body must be a JSON object");

        URI upstreamUri;
        try {
            upstreamUri = buildUpstreamUri(credential.baseUrl(), routing.apiProtocol());
        } catch (IllegalArgumentException e) {
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("Invalid upstream configuration");
        }

        LlmConnectionHealth.Permit permit = health.acquire(routing.connectionScope(), routing.connectionId());
        if (permit == null) {
            incrementErrors(routing.apiProtocol());
            return providerError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    UPSTREAM_PAUSED,
                    "the model provider failed repeatedly, so Hephaestus waits up to "
                            + LlmConnectionHealth.OPEN_FOR.toSeconds() + " seconds before it calls it again");
        }
        span.tag("gen_ai.request.model", credential.upstreamModelId());
        Exchange exchange = new Exchange(
                upstreamUri,
                buildUpstreamHeaders(incomingHeaders, credential),
                routing.attempt().sourceType() == LlmUsageSourceType.MENTOR_TURN ? hephBounds : null,
                call.cancelled());
        ProxyStreamUsageTap tap = new ProxyStreamUsageTap(objectMapper, responsesProtocol);
        UpstreamResult upstream;
        try {
            upstream = callUpstream(exchange, prepared.body(), span, response, tap);
            if (rejectedOurUsageRequest(upstream, prepared)) {
                // Asking for usage is OUR addition, so refusing it must cost the caller nothing.
                log.info(
                        "Upstream rejected stream_options.include_usage for principal {}; retrying without it — "
                                + "this call's tokens will not be metered",
                        routing.principalDescription());
                accounting.recordStreamUsageUnsupported(routing.apiProtocol());
                if (!requestPolicy.allows(routing)) {
                    permit.released();
                    return ResponseEntity.status(403)
                            .body("Your AI choice no longer permits this model in this workspace.");
                }
                upstream = callUpstream(exchange, prepared.withoutUsageRequestOrBody(), span, response, tap);
            }
        } catch (RuntimeException e) {
            permit.failed(e);
            incrementErrors(routing.apiProtocol());
            if (ProxyStreamingUtils.readTimedOut(e)) {
                accounting.recordUpstreamStalled(routing.apiProtocol());
                String stall = "the model provider sent no first byte within " + seconds(exchange, Bounds::firstByte);
                log.warn("LLM upstream stalled for principal {}: {}", routing.principalDescription(), stall);
                return providerError(HttpStatus.GATEWAY_TIMEOUT, UPSTREAM_STALLED, stall);
            }
            log.warn(
                    "LLM upstream request failed for principal {}: reason={}",
                    routing.principalDescription(),
                    e.getClass().getSimpleName());
            return e instanceof WebClientRequestException
                    ? ResponseEntity.status(502).body("Upstream provider unreachable")
                    : ResponseEntity.status(502).body("Upstream request failed");
        }

        if (upstream == null) {
            // The turn's end cancelled the call before the provider answered.
            permit.released();
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("Upstream provider unavailable");
        }
        if (call.isCancelled()) {
            // The turn ended without this call; the provider's state is unknown.
            permit.released();
        } else {
            judge(upstream, permit);
        }
        if (upstream.streamOutcome() == StreamOutcome.UPSTREAM_IDLE) {
            accounting.recordUpstreamStalled(routing.apiProtocol());
            String stall = "the model provider's stream sent nothing for " + seconds(exchange, Bounds::idle);
            log.warn("LLM upstream stalled for principal {}: {}", routing.principalDescription(), stall);
            writeStallFrame(response, stall);
        }
        return relayed(routing, upstream, tap, span);
    }

    /**
     * Tells the connection's breaker how the provider did: a stall, a broken stream or a server error counts against
     * it; a request error or a client that went away says nothing about the provider.
     */
    private static void judge(UpstreamResult upstream, LlmConnectionHealth.Permit permit) {
        StreamOutcome outcome = upstream.streamOutcome();
        if (upstream.status() >= 500) {
            permit.failed(new IllegalStateException("upstream status " + upstream.status()));
        } else if (outcome == StreamOutcome.UPSTREAM_IDLE || outcome == StreamOutcome.STREAM_FAILED) {
            permit.failed(new IllegalStateException("upstream stream " + outcome));
        } else if (upstream.status() >= 400 || outcome == StreamOutcome.CLIENT_DISCONNECTED) {
            permit.released();
        } else {
            permit.succeeded();
        }
    }

    private @Nullable ResponseEntity<?> relayed(
            ProxyRouting routing, UpstreamResult upstream, ProxyStreamUsageTap tap, Span span) {
        ProxyRouting.BilledAttempt attempt = routing.attempt();
        boolean responsesProtocol = RESPONSES_PROTOCOL.equals(routing.apiProtocol());
        boolean served = upstream.status() >= 200 && upstream.status() < 300;
        if (!served) {
            // The sandbox only sees "error" from its model client; the trace carries the status on the
            // span and this is its log-side counterpart, for an operator reading logs rather than
            // traces. Status and principal only: the body can quote the request.
            log.warn(
                    "LLM upstream answered a call for principal {} with status={}",
                    routing.principalDescription(),
                    upstream.status());
        }
        var outcome = upstream.streamOutcome();
        if (outcome != null) {
            span.tag("hephaestus.stream.outcome", outcome.name());
            if (outcome != StreamOutcome.COMPLETED) {
                recordSpanError(span, outcome.name());
                incrementErrors(routing.apiProtocol());
            }
            if (served) {
                if (tap.hasMalformedUsage()) {
                    accounting.recordMalformedUsage(attempt);
                } else {
                    accounting.recordUsage(attempt, tap.observed());
                    ProxyTokenUsage usage = tap.observed();
                    if (usage != null) {
                        span.tag(
                                "gen_ai.usage.input_tokens",
                                (long) usage.billableInputTokens()
                                        + usage.cacheReadTokens()
                                        + usage.cacheWriteTokens());
                        span.tag("gen_ai.usage.output_tokens", usage.outputTokens());
                    }
                }
            }
            return null;
        }
        if (!served) {
            incrementErrors(routing.apiProtocol());
        }
        // Attributed now, not at the run's terminal write, so an execution that dies still bills.
        if (upstream.body() != null && served) {
            accounting.recordUsage(attempt, upstream.body(), responsesProtocol);
        }
        return ResponseEntity.status(upstream.status())
                .headers(upstream.headers())
                .body(upstream.body());
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
     * @param bounds the silence the call is cut after; {@code null} for the client's own read bound
     * @param cancelled completes when the attempt's end cancels the call
     */
    private record Exchange(
            URI uri, HttpHeaders headers, @Nullable Bounds bounds, Mono<Void> cancelled) {}

    private @Nullable UpstreamResult callUpstream(
            Exchange exchange, byte[] outgoingBody, Span span, HttpServletResponse response, ProxyStreamUsageTap tap) {
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
                : stream -> stream.takeUntilOther(exchange.cancelled()).timeout(bounds.idle());
        AtomicBoolean answered = new AtomicBoolean();
        return request.bodyValue(outgoingBody)
                .exchangeToMono(upstream -> {
                    answered.set(true);
                    return ProxyStreamingUtils.consumeResponse(upstream, response, tap, body);
                })
                // Before the response, a cancel drops the exchange; after it, the body stops and the stream ends.
                .takeUntilOther(exchange.cancelled()
                        .then(Mono.defer(() -> answered.get() ? Mono.never() : Mono.just(Boolean.TRUE))))
                .block(BLOCK_TIMEOUT.plus(ProxyStreamingUtils.DEFAULT_SSE_TIMEOUT));
    }

    private static String seconds(Exchange exchange, Function<Bounds, Duration> bound) {
        Bounds bounds = exchange.bounds();
        return bounds == null ? "the client's read bound" : bound.apply(bounds).toSeconds() + " seconds";
    }

    /**
     * An error a model client reads as the provider's: a JSON body with {@code error}, whose message starts with the
     * code. The Heph host recognizes the codes ({@code heph-host.ts}).
     */
    private ResponseEntity<String> providerError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(errorPayload(code, message)));
    }

    private ObjectNode errorPayload(String code, String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("error")
                .put("message", code + ": " + message)
                .put("type", code)
                .put("code", code);
        return payload;
    }

    /**
     * Ends a stream whose provider went silent with an error event. The response headers are already sent, and a
     * stream that just stops would read as a broken one; both OpenAI protocols' clients raise this event as an error.
     */
    private void writeStallFrame(HttpServletResponse response, String message) {
        try {
            OutputStream out = response.getOutputStream();
            out.write(("data: " + objectMapper.writeValueAsString(errorPayload(UPSTREAM_STALLED, message)) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException clientGone) {
            log.debug("The client left before the stall was reported");
        }
    }

    /** Narrow on purpose: a blanket retry on 4xx would double every bad request the runner makes. */
    private static boolean rejectedOurUsageRequest(@Nullable UpstreamResult upstream, PreparedBody prepared) {
        if (upstream == null || prepared.withoutUsageRequest() == null) return false;
        if (upstream.status() != 400 && upstream.status() != 422) return false;
        byte[] body = upstream.body();
        return body != null && new String(body, StandardCharsets.UTF_8).contains("stream_options");
    }

    private static @Nullable ResponseEntity<String> validateSafeSurface(
            HttpServletRequest request, ProxyRouting routing) {
        if (!"POST".equals(request.getMethod()))
            return ResponseEntity.status(405).body("Method not allowed");
        if (request.getQueryString() != null)
            return ResponseEntity.badRequest().body("Query parameters are not allowed");

        String expectedPath =
                switch (routing.apiProtocol()) {
                    case COMPLETIONS_PROTOCOL -> COMPLETIONS_PROXY_PATH;
                    case RESPONSES_PROTOCOL -> RESPONSES_PROXY_PATH;
                    default -> null;
                };
        if (expectedPath == null || !expectedPath.equals(request.getRequestURI())) {
            return ResponseEntity.status(404).body("Not found");
        }
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

    static URI buildUpstreamUri(String baseUrl, String apiProtocol) {
        String suffix =
                switch (apiProtocol) {
                    case COMPLETIONS_PROTOCOL -> "/chat/completions";
                    case RESPONSES_PROTOCOL -> "/responses";
                    default -> throw new IllegalArgumentException("Unsupported API protocol");
                };
        return URI.create(baseUrl.strip().replaceAll("/+$", "") + suffix);
    }

    private ProxyRouting authenticatedRouting() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JobTokenAuthentication tokenAuthentication) {
            return tokenAuthentication.getPrincipal();
        }
        throw new IllegalStateException("Expected JobTokenAuthentication on security context");
    }

    /** Names WHICH purse stopped the call, because the two have different remedies. */
    private static String budgetReachedMessage(@Nullable FundingSource fundingSource) {
        return fundingSource == FundingSource.WORKSPACE
                ? "Own-provider budget reached. Paused until an admin raises the cap or the month rolls over."
                : "Shared-model budget reached. Paused until an admin raises the budget or the month rolls over.";
    }

    private static void recordSpanError(Span span, String errorType) {
        // The original exception may contain provider content or credentials. Only a bounded failure
        // classification is exported; Micrometer's error API also sets the underlying OTLP status.
        span.error(new IllegalStateException(errorType));
        span.tag("error.type", errorType);
    }

    private void incrementErrors(String apiProtocol) {
        accounting.recordError(apiProtocol);
    }
}
