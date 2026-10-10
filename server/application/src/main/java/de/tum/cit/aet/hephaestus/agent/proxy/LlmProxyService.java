package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.UpstreamResult;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Credential-injecting proxy for the model calls of agent sandboxes and their precompute scripts.
 * The authenticated token chooses a catalog model; callers cannot choose an upstream host, path,
 * protocol, credential header, or model id.
 */
@Service
@ConditionalOnWorkerRole
class LlmProxyService {

    private static final Logger log = LoggerFactory.getLogger(LlmProxyService.class);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(310);
    private static final String PROXY_PATH_PREFIX = "/internal/llm";

    private final WebClient webClient;
    private final LlmModelResolver resolver;
    private final ObjectMapper objectMapper;
    private final ProxyAccounting accounting;
    private final ProxyRequestPolicy requestPolicy;
    private final Tracer tracer;

    LlmProxyService(
            Tracer tracer,
            WebClient llmProxyWebClient,
            LlmModelResolver llmModelResolver,
            ObjectMapper objectMapper,
            ProxyAccounting accounting,
            ProxyRequestPolicy requestPolicy) {
        this.tracer = tracer;
        this.webClient = llmProxyWebClient;
        this.resolver = llmModelResolver;
        this.objectMapper = objectMapper;
        this.accounting = accounting;
        this.requestPolicy = requestPolicy;
    }

    public @Nullable ResponseEntity<?> proxy(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte @Nullable [] body) {
        ProxyRouting routing = authenticatedRouting();
        ResponseEntity<String> rejected = validateSafeSurface(request);
        if (rejected != null) return rejected;
        LlmApiProtocol protocol = LlmApiProtocol.parse(routing.apiProtocol())
                .filter(parsed -> parsed.serves(ModelKind.CHAT))
                .orElse(null);
        if (routing.precomputeSlot() != null
                || protocol == null
                || !(PROXY_PATH_PREFIX + protocol.upstreamPath()).equals(request.getRequestURI())) {
            return ResponseEntity.status(404).body("Not found");
        }
        if (body == null || body.length == 0) return ResponseEntity.badRequest().body("Request body is required");
        rejected = validateJsonObject(body);
        if (rejected != null) return rejected;
        return serve(routing, protocol, null, response, incomingHeaders, body);
    }

    /**
     * Serves one call of a precompute script on {@code /internal/llm/precompute/{slot}/{op}}. The op must
     * be the path of the slot's frozen protocol. The call must name its practice in
     * {@link JobTokenAuthenticationFilter#PRECOMPUTE_PRACTICE_HEADER}. The call is never streamed, so its
     * usage is always read from the response body.
     */
    public @Nullable ResponseEntity<?> proxyPrecompute(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte @Nullable [] body) {
        ProxyRouting routing = authenticatedRouting();
        ResponseEntity<String> rejected = validateSafeSurface(request);
        if (rejected != null) return rejected;
        ModelKind slot = routing.precomputeSlot();
        LlmApiProtocol protocol = LlmApiProtocol.parse(routing.apiProtocol())
                .filter(parsed -> slot != null && parsed.serves(slot))
                .orElse(null);
        if (slot == null || protocol == null) {
            return ResponseEntity.status(404).body("Not found");
        }
        String slotPath = JobTokenAuthenticationFilter.PRECOMPUTE_PATH + slot.slot();
        if (!(slotPath + protocol.upstreamPath()).equals(request.getRequestURI())) {
            return ResponseEntity.badRequest().body("This model takes calls on " + slotPath + protocol.upstreamPath());
        }
        String practice = incomingHeaders.getFirst(JobTokenAuthenticationFilter.PRECOMPUTE_PRACTICE_HEADER);
        if (practice == null || !SandboxLayout.PRACTICE_SLUG.matcher(practice).matches()) {
            accounting.recordUnattributedPrecompute();
            return ResponseEntity.badRequest()
                    .body("A precompute call must name its practice in "
                            + JobTokenAuthenticationFilter.PRECOMPUTE_PRACTICE_HEADER);
        }
        if (body == null || body.length == 0) return ResponseEntity.badRequest().body("Request body is required");
        rejected = validateJsonObject(body);
        if (rejected != null) return rejected;
        JsonNode call = objectMapper.readTree(body);
        if (call.path("stream").asBoolean(false)) {
            return ResponseEntity.badRequest().body("Precompute calls cannot stream");
        }
        String tooManyEntries = tooManyEntries(call, protocol);
        if (tooManyEntries != null)
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(tooManyEntries);
        return serve(
                routing,
                protocol,
                new PrecomputeCall(slot, routing.precomputeTier(), practice),
                response,
                incomingHeaders,
                body);
    }

    // MODEL_SLOT_CAPS in docker/agents/precompute/lib/contract.ts holds the same caps, so the runner refuses first.
    static final int MAX_DECISION_QUESTIONS = 64;
    static final int MAX_EMBEDDING_INPUTS = 64;
    static final int MAX_RERANK_DOCUMENTS = 256;

    /** @return why the call holds more entries than one call to its model may send, or {@code null} */
    private static @Nullable String tooManyEntries(JsonNode call, LlmApiProtocol protocol) {
        return switch (protocol) {
            case OPENAI_DECISIONS -> tooManyEntries(call, "questions", MAX_DECISION_QUESTIONS);
            case OPENAI_EMBEDDINGS -> tooManyEntries(call, "input", MAX_EMBEDDING_INPUTS);
            case COHERE_RERANK -> tooManyEntries(call, "documents", MAX_RERANK_DOCUMENTS);
            case OPENAI_COMPLETIONS, OPENAI_RESPONSES -> null;
        };
    }

    /** Only an array can hold too many entries. Any other shape is left for the provider to refuse. */
    private static @Nullable String tooManyEntries(JsonNode call, String field, int max) {
        JsonNode entries = call.get(field);
        if (entries == null || !entries.isArray() || entries.size() <= max) return null;
        return "'" + field + "' must hold at most " + max + " entries";
    }

    /** @param precompute {@code null} for a call of the review or of Heph */
    @SuppressWarnings("try") // The scope installs the current span and restores it on close.
    private @Nullable ResponseEntity<?> serve(
            ProxyRouting routing,
            LlmApiProtocol protocol,
            @Nullable PrecomputeCall precompute,
            HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte[] body) {
        accounting.recordGatewayRequest(body.length);

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
        try (var ignored = tracer.withSpan(span)) {
            var result = forward(routing, protocol, precompute, response, incomingHeaders, body, span);
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
            @Nullable PrecomputeCall precompute,
            HttpServletResponse response,
            HttpHeaders incomingHeaders,
            byte[] body,
            Span span) {
        // With no attempt there is no row to accumulate onto, so a served call's tokens would reach
        // neither the ledger nor the cap that reads it.
        ProxyRouting.BilledAttempt attempt = routing.attempt();
        if (attempt == null) {
            accounting.recordUnbillableRefusal(routing.apiProtocol());
            log.warn("Refusing an LLM call with no billing target: principal {}", routing.principalDescription());
            return ResponseEntity.status(403).body("This credential is not running a billable execution");
        }

        if (!requestPolicy.allows(routing)) {
            return ResponseEntity.status(403).body("Your AI choice no longer permits this model in this workspace.");
        }

        // A precompute call is refused with 402, which no rate limit answers, so its runner can tell a
        // spent budget from the gateway's or the provider's 429. The AI SDK also never retries a 402.
        HttpStatus spendingRefusal = precompute == null ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.PAYMENT_REQUIRED;
        // Before any credential is resolved or the network touched. Never interrupts a live stream.
        if (accounting.refuseForBudget(routing)) {
            return ResponseEntity.status(spendingRefusal).body(budgetReachedMessage(routing.connectionScope()));
        }
        if (precompute != null && accounting.refuseForPrecomputeCap(routing, attempt)) {
            return ResponseEntity.status(spendingRefusal).body("Precompute budget spent");
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

        boolean responsesProtocol = protocol == LlmApiProtocol.OPENAI_RESPONSES;
        PreparedBody prepared = prepareBody(body, credential.upstreamModelId(), !responsesProtocol);
        if (prepared == null) return ResponseEntity.badRequest().body("Request body must be a JSON object");

        URI upstreamUri;
        try {
            upstreamUri = buildUpstreamUri(credential.baseUrl(), protocol);
        } catch (IllegalArgumentException e) {
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("Invalid upstream configuration");
        }

        span.tag("gen_ai.request.model", credential.upstreamModelId());
        HttpHeaders upstreamHeaders = buildUpstreamHeaders(incomingHeaders, credential);
        ProxyStreamUsageTap tap = new ProxyStreamUsageTap(objectMapper, responsesProtocol);
        UpstreamResult upstream;
        try {
            upstream = callUpstream(upstreamUri, upstreamHeaders, prepared.body(), span, response, tap);
            if (rejectedOurUsageRequest(upstream, prepared)) {
                // Asking for usage is OUR addition, so refusing it must cost the caller nothing.
                log.info(
                        "Upstream rejected stream_options.include_usage for principal {}; retrying without it — "
                                + "this call's tokens will not be metered",
                        routing.principalDescription());
                accounting.recordStreamUsageUnsupported(routing.apiProtocol());
                if (!requestPolicy.allows(routing))
                    return ResponseEntity.status(403)
                            .body("Your AI choice no longer permits this model in this workspace.");
                upstream = callUpstream(
                        upstreamUri, upstreamHeaders, prepared.withoutUsageRequestOrBody(), span, response, tap);
            }
        } catch (WebClientRequestException e) {
            log.warn(
                    "LLM upstream unreachable for principal {}: reason={}",
                    routing.principalDescription(),
                    e.getClass().getSimpleName());
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("Upstream provider unreachable");
        } catch (Exception e) {
            log.warn(
                    "LLM upstream request failed for principal {}: reason={}",
                    routing.principalDescription(),
                    e.getClass().getSimpleName());
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("Upstream request failed");
        }

        if (upstream == null) {
            incrementErrors(routing.apiProtocol());
            return ResponseEntity.status(502).body("Upstream provider unavailable");
        }
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
            if (outcome != ProxyStreamingUtils.StreamOutcome.COMPLETED) {
                recordSpanError(span, outcome.name());
                incrementErrors(routing.apiProtocol());
            }
            if (served) {
                if (tap.hasMalformedUsage()) {
                    accounting.recordMalformedUsage(attempt);
                } else {
                    accounting.recordUsage(attempt, precompute, tap.observed());
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
            accounting.recordUsage(attempt, precompute, protocol, upstream.body());
        }
        return ResponseEntity.status(upstream.status())
                .headers(upstream.headers())
                .body(upstream.body());
    }

    private @Nullable UpstreamResult callUpstream(
            URI uri,
            HttpHeaders upstreamHeaders,
            byte[] outgoingBody,
            Span span,
            HttpServletResponse response,
            ProxyStreamUsageTap tap) {
        span.event("upstream.request");
        return webClient
                .method(HttpMethod.POST)
                .uri(uri)
                .headers(headers -> {
                    headers.clear();
                    headers.addAll(upstreamHeaders);
                })
                .bodyValue(outgoingBody)
                .exchangeToMono(upstream -> ProxyStreamingUtils.consumeResponse(upstream, response, tap))
                .block(BLOCK_TIMEOUT.plus(ProxyStreamingUtils.DEFAULT_SSE_TIMEOUT));
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
