package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract: the probe never throws on an upstream failure — a 4xx, 5xx or timeout comes back as an
 * advisory {@link LlmProbeResultDTO} with {@code reachable=false}. Only the egress guard may reject the
 * request, before any network call. A provider on the operator's private network gets no status and no
 * cause back, only reachable or not: anything more would let an admin map that network.
 */
@Service
@WorkspaceAgnostic("Instance LLM connection probe reads the global connection catalog, not tenant data")
public class LlmConnectionProbeService {

    private static final Logger log = LoggerFactory.getLogger(LlmConnectionProbeService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_MODELS = 1_000;
    private static final int MAX_MODEL_ID_LENGTH = 256;
    private static final String PRIVATE_UNREACHABLE =
            "Hephaestus could not reach the provider. The server log has the reason.";

    private final EgressPolicy egressPolicy;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    LlmConnectionProbeService(EgressPolicy egressPolicy, ObjectMapper objectMapper, RestClient.Builder restClients) {
        this.egressPolicy = egressPolicy;
        this.objectMapper = objectMapper;
        // followRedirect(false): only the initial URL is egress-validated, and the auth header would
        // otherwise survive a cross-origin redirect and hand the credential to whatever the 3xx names.
        // The guarded resolver re-runs the private-address check on the resolution actually connected
        // to, closing the DNS-rebind window that validate-time checking leaves open.
        HttpClient httpClient =
                HttpClient.create().resolver(egressPolicy.resolverGroup()).followRedirect(false);
        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(PROBE_TIMEOUT);
        factory.setReadTimeout(PROBE_TIMEOUT);
        this.restClient = restClients.requestFactory(factory).build();
    }

    public LlmProbeResultDTO probeDraft(ProbeLlmConnectionRequestDTO request) {
        boolean privateTarget = egressPolicy.validate(request.baseUrl());
        LlmAuthMode authMode = request.authMode() != null ? request.authMode() : LlmAuthMode.BEARER;
        return probe(
                        new LlmProbeTarget(request.baseUrl(), authMode, request.apiKey(), request.apiProtocol()),
                        privateTarget)
                .result();
    }

    /** What a probe of a stored connection found: the listing, and the limits of the models asked for. */
    record Probe(LlmProbeResultDTO result, Map<String, ProviderModelLimits.Limits> limits) {}

    /**
     * Tests a connection and reads the token limits of the given models from the provider's metadata
     * ({@link ProviderModelLimits}). The listing answers first. For a model it reports no limits for, an
     * OpenAI-compatible connection then asks LiteLLM's {@code /model/info} and, under a {@code /v1} base URL,
     * Ollama's {@code /api/show}. A lookup that fails finds nothing; it never changes the probe's result.
     * The caller must have egress-validated {@code target.baseUrl()} itself.
     */
    Probe probeWithLimits(LlmProbeTarget target, boolean privateTarget, Collection<String> upstreamModelIds) {
        Listing listing = probe(target, privateTarget);
        if (!listing.result().reachable() || upstreamModelIds.isEmpty()) return new Probe(listing.result(), Map.of());
        LlmApiProtocol protocol = target.apiProtocol();
        Map<String, ProviderModelLimits.Limits> listed = ProviderModelLimits.fromModelList(protocol, listing.body());
        Map<String, ProviderModelLimits.Limits> limits = new HashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String id : upstreamModelIds) {
            var found = listed.getOrDefault(id, ProviderModelLimits.Limits.NONE);
            if (!found.empty()) limits.put(id, found);
            // The catalog covers a model whose provider lists no limits; asking further would only cost time.
            if ((found.maxOutputTokens() == null || found.contextWindow() == null)
                    && ModelLimitCatalog.find(id) == null) missing.add(id);
        }
        if (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES || missing.isEmpty())
            return new Probe(listing.result(), reported(listing.result(), upstreamModelIds, limits));
        String base = stripTrailingSlash(target.baseUrl());
        Map<String, ProviderModelLimits.Limits> liteLlm =
                ProviderModelLimits.fromLiteLlmModelInfo(fetchJson(HttpMethod.GET, base + "/model/info", target, null));
        for (String id : List.copyOf(missing)) {
            var merged = limits.getOrDefault(id, ProviderModelLimits.Limits.NONE)
                    .or(liteLlm.getOrDefault(id, ProviderModelLimits.Limits.NONE));
            if (!merged.empty()) limits.put(id, merged);
            if (merged.contextWindow() != null) missing.remove(id);
        }
        if (!base.endsWith("/v1"))
            return new Probe(listing.result(), reported(listing.result(), upstreamModelIds, limits));
        String ollama = base.substring(0, base.length() - "/v1".length()) + "/api/show";
        for (String id : missing) {
            JsonNode shown = fetchJson(HttpMethod.POST, ollama, target, Map.of("model", id));
            // A server without /api/show answers for no model: ask no further.
            if (shown == null) break;
            var merged = limits.getOrDefault(id, ProviderModelLimits.Limits.NONE)
                    .or(ProviderModelLimits.fromOllamaShow(shown));
            if (!merged.empty()) limits.put(id, merged);
        }
        return new Probe(listing.result(), reported(listing.result(), upstreamModelIds, limits));
    }

    /**
     * The limits found for each model asked for that the listing names, absent limits included: a model the provider
     * still serves gets what it reports now, so a window that shrank does not stay at its old size.
     */
    private static Map<String, ProviderModelLimits.Limits> reported(
            LlmProbeResultDTO result,
            Collection<String> upstreamModelIds,
            Map<String, ProviderModelLimits.Limits> limits) {
        Set<String> listed = Set.copyOf(result.models());
        Map<String, ProviderModelLimits.Limits> reported = new HashMap<>(limits);
        for (String id : upstreamModelIds)
            if (listed.contains(id)) reported.putIfAbsent(id, ProviderModelLimits.Limits.NONE);
        return reported;
    }

    private record Listing(
            LlmProbeResultDTO result, @Nullable JsonNode body) {}

    private Listing probe(LlmProbeTarget target, boolean privateTarget) {
        LlmApiProtocol protocol = target.apiProtocol();
        // Anthropic lists 20 models a page by default.
        String url = stripTrailingSlash(target.baseUrl())
                + (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES ? "/v1/models?limit=" + MAX_MODELS : "/models");
        try {
            return restClient
                    .get()
                    .uri(url)
                    .headers(headers -> authenticate(headers, target))
                    .exchange((clientRequest, clientResponse) -> {
                        int status = clientResponse.getStatusCode().value();
                        if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                            if (privateTarget) {
                                log.info("LLM connection probe of a private host failed: status={}", status);
                                return new Listing(LlmProbeResultDTO.unreachable(null, PRIVATE_UNREACHABLE), null);
                            }
                            return new Listing(
                                    LlmProbeResultDTO.unreachable(
                                            status, "The provider answered with HTTP " + status + "."),
                                    null);
                        }
                        byte[] response = clientResponse.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (response.length > MAX_RESPONSE_BYTES) {
                            if (privateTarget) {
                                log.info("LLM connection probe of a private host failed: response too large");
                                return new Listing(LlmProbeResultDTO.unreachable(null, PRIVATE_UNREACHABLE), null);
                            }
                            return new Listing(
                                    LlmProbeResultDTO.unreachable(status, "The provider’s response was too large."),
                                    null);
                        }
                        JsonNode body = objectMapper.readTree(response);
                        return new Listing(LlmProbeResultDTO.reachable(extractModelIds(body), status), body);
                    });
        } catch (Exception e) {
            // The exception message may carry host detail, so keep it out of the response.
            log.info("LLM connection probe failed: reason={}", e.getClass().getSimpleName());
            if (privateTarget) {
                return new Listing(LlmProbeResultDTO.unreachable(null, PRIVATE_UNREACHABLE), null);
            }
            return new Listing(
                    LlmProbeResultDTO.unreachable(
                            null,
                            "Hephaestus could not reach the provider ("
                                    + e.getClass().getSimpleName() + ")."),
                    null);
        }
    }

    /** A JSON answer of a metadata lookup on the connection's host; {@code null} when it fails in any way. */
    private @Nullable JsonNode fetchJson(
            HttpMethod method, String url, LlmProbeTarget target, @Nullable Map<String, String> body) {
        try {
            var request = restClient.method(method).uri(url).headers(headers -> authenticate(headers, target));
            if (body != null)
                request = request.contentType(MediaType.APPLICATION_JSON).body(body);
            return request.exchange((clientRequest, clientResponse) -> {
                if (!clientResponse.getStatusCode().is2xxSuccessful()) return null;
                byte[] response = clientResponse.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.length > MAX_RESPONSE_BYTES) return null;
                JsonNode parsed = objectMapper.readTree(response);
                return parsed.isObject() ? parsed : null;
            });
        } catch (Exception e) {
            // A server that has no such endpoint answers with an error or an HTML page: neither is a finding.
            log.debug(
                    "LLM model metadata lookup found nothing: reason={}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private static void authenticate(HttpHeaders headers, LlmProbeTarget target) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String apiKey = target.apiKey();
        boolean anthropic = target.apiProtocol() == LlmApiProtocol.ANTHROPIC_MESSAGES;
        if (anthropic) headers.set("anthropic-version", "2023-06-01");
        if (!StringUtils.hasText(apiKey)) return;
        if (anthropic) headers.set("x-api-key", apiKey);
        else if (target.authMode() == LlmAuthMode.API_KEY) headers.set("api-key", apiKey);
        else headers.setBearerAuth(apiKey);
    }

    private static List<String> extractModelIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        if (body == null) {
            return ids;
        }
        for (JsonNode entry : body.path("data")) {
            String id = entry.path("id").asString("");
            if (!id.isBlank() && id.length() <= MAX_MODEL_ID_LENGTH) {
                ids.add(id);
                if (ids.size() == MAX_MODELS) {
                    break;
                }
            }
        }
        return ids;
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
