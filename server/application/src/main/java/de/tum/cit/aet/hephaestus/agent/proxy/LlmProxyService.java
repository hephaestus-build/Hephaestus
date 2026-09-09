package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.EgressPolicy;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.job.ExecutionArchiveService;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.UpstreamResult;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.fabric.ContentAddressedStore;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Credential-injecting proxy for the two OpenAI-compatible API surfaces used by agent sandboxes.
 * The authenticated token chooses a catalog model; callers cannot choose an upstream host, path,
 * protocol, credential header, or model id.
 */
@org.springframework.stereotype.Service
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
class LlmProxyService {

    private static final Logger log = LoggerFactory.getLogger(LlmProxyService.class);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(310);
    private static final String COMPLETIONS_PROTOCOL = "openai-completions";
    private static final String RESPONSES_PROTOCOL = "openai-responses";
    private static final String COMPLETIONS_PROXY_PATH = "/internal/llm/chat/completions";
    private static final String RESPONSES_PROXY_PATH = "/internal/llm/responses";

    private final WebClient webClient;
    private final LlmModelResolver resolver;
    private final EgressPolicy egressPolicy;
    private final ObjectMapper objectMapper;
    private final ProxyAccounting accounting;
    private final Tracer tracer;
    private final ExecutionArchiveService executionArchive;

    LlmProxyService(
            Tracer tracer,
            ExecutionArchiveService executionArchive,
            WebClient llmProxyWebClient,
            LlmModelResolver llmModelResolver,
            EgressPolicy egressPolicy,
            ObjectMapper objectMapper,
            ProxyAccounting accounting) {
        this.tracer = tracer;
        this.executionArchive = executionArchive;
        this.webClient = llmProxyWebClient;
        this.resolver = llmModelResolver;
        this.egressPolicy = egressPolicy;
        this.objectMapper = objectMapper;
        this.accounting = accounting;
    }

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
                .tag("hephaestus.pi_request.sha256", ContentAddressedStore.sha256(body))
                .start();
        if (routing.sourceId() != null)
            span.tag("hephaestus.job.id", routing.sourceId().toString());
        if (routing.workspaceId() != null) span.tag("hephaestus.workspace.id", routing.workspaceId());
        try (var ignored = tracer.withSpan(span)) {
            var result = forward(routing, response, incomingHeaders, body, span);
            int status = result == null
                    ? response.getStatus()
                    : result.getStatusCode().value();
            span.tag("http.response.status_code", status);
            if (status >= 400) span.tag("error.type", Integer.toString(status));
            return result;
        } catch (RuntimeException e) {
            span.tag("error.type", e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
            accounting.stopTimer(timer, routing.apiProtocol());
            MDC.remove("proxy.principal");
            MDC.remove("proxy.apiProtocol");
        }
    }

    private @Nullable ResponseEntity<?> forward(
            ProxyRouting routing, HttpServletResponse response, HttpHeaders incomingHeaders, byte[] body, Span span) {
        // With no attempt there is no row to accumulate onto, so a served call's tokens would reach
        // neither the ledger nor the cap that reads it.
        ProxyRouting.BilledAttempt attempt = routing.attempt();
        if (attempt == null) {
            accounting.recordUnbillableRefusal(routing.apiProtocol());
            log.warn("Refusing an LLM call with no billing target: principal {}", routing.principalDescription());
            return ResponseEntity.status(403).body("This credential is not running a billable execution");
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
            egressPolicy.validate(credential.baseUrl());
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

        span.tag("gen_ai.request.model", credential.upstreamModelId());
        HttpHeaders upstreamHeaders = buildUpstreamHeaders(incomingHeaders, credential);
        ProxyStreamUsageTap tap = new ProxyStreamUsageTap(objectMapper, responsesProtocol);
        UpstreamResult upstream;
        try {
            upstream = callUpstream(upstreamUri, upstreamHeaders, prepared.body(), routing, span, body, response, tap);
            if (rejectedOurUsageRequest(upstream, prepared)) {
                // Asking for usage is OUR addition, so refusing it must cost the caller nothing.
                log.info(
                        "Upstream rejected stream_options.include_usage for principal {}; retrying without it — "
                                + "this call's tokens will not be metered",
                        routing.principalDescription());
                accounting.recordStreamUsageUnsupported(routing.apiProtocol());
                upstream = callUpstream(
                        upstreamUri,
                        upstreamHeaders,
                        prepared.withoutUsageRequestOrBody(),
                        routing,
                        span,
                        body,
                        response,
                        tap);
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
        if (upstream.streamed()) {
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
        // Attributed now, not at the run's terminal write, so an execution that dies still bills.
        if (upstream.body() != null && served) {
            accounting.recordUsage(attempt, upstream.body(), responsesProtocol);
        }
        return ResponseEntity.status(upstream.status())
                .headers(upstream.headers())
                .body(upstream.body());
    }

    private @Nullable UpstreamResult callUpstream(
            URI uri,
            HttpHeaders upstreamHeaders,
            byte[] outgoingBody,
            ProxyRouting routing,
            Span span,
            byte[] incomingBody,
            HttpServletResponse response,
            ProxyStreamUsageTap tap) {
        var attempt = routing.attempt();
        Long workspaceId = routing.workspaceId();
        if (attempt != null && workspaceId != null && attempt.sourceType() == LlmUsageSourceType.AGENT_JOB) {
            executionArchive.captureProxyRequest(
                    workspaceId,
                    attempt.sourceId(),
                    attempt.number(),
                    outgoingBody,
                    ContentAddressedStore.sha256(incomingBody),
                    span.context());
        }
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

    private void incrementErrors(String apiProtocol) {
        accounting.recordError(apiProtocol);
    }
}
