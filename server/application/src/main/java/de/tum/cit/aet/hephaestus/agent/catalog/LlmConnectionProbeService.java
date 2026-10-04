package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * request, before any network call.
 */
@Service
@WorkspaceAgnostic("Instance LLM connection probe reads the global connection catalog, not tenant data")
public class LlmConnectionProbeService {

    private static final Logger log = LoggerFactory.getLogger(LlmConnectionProbeService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_MODELS = 1_000;
    private static final int MAX_MODEL_ID_LENGTH = 256;

    private final LlmConnectionRepository connectionRepository;
    private final EgressPolicy egressPolicy;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    LlmConnectionProbeService(
            LlmConnectionRepository connectionRepository,
            EgressPolicy egressPolicy,
            ObjectMapper objectMapper,
            LlmProperties llmProperties) {
        boolean allowLoopback = llmProperties.egress().allowLoopback();
        this.connectionRepository = connectionRepository;
        this.egressPolicy = egressPolicy;
        this.objectMapper = objectMapper;
        // followRedirect(false): only the initial URL is egress-validated, and the auth header would
        // otherwise survive a cross-origin redirect and hand the credential to whatever the 3xx names.
        // The guarded resolver re-runs the private-address check on the resolution actually connected
        // to, closing the DNS-rebind window that validate-time checking leaves open.
        HttpClient httpClient = HttpClient.create()
                .resolver(WebClientConnectors.resolverGroup(allowLoopback))
                .followRedirect(false);
        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(PROBE_TIMEOUT);
        factory.setReadTimeout(PROBE_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Deliberately NOT {@code @Transactional}: wrapping it would park a pooled JDBC connection for the
     * full network timeout of every probe.
     */
    public LlmProbeResultDTO probeStored(Long connectionId) {
        LlmProbeTarget target = connectionRepository
                .findProbeTargetById(connectionId)
                .orElseThrow(() -> new EntityNotFoundException("LlmConnection", connectionId));
        egressPolicy.validate(target.baseUrl());
        return probe(target.baseUrl(), target.authMode(), target.apiKey());
    }

    public LlmProbeResultDTO probeDraft(ProbeLlmConnectionRequestDTO request) {
        egressPolicy.validate(request.baseUrl());
        LlmAuthMode authMode = request.authMode() != null ? request.authMode() : LlmAuthMode.BEARER;
        return probe(request.baseUrl(), authMode, request.apiKey());
    }

    /** The caller (workspace BYO) must have egress-validated {@code baseUrl} itself. */
    public LlmProbeResultDTO probeCredential(String baseUrl, LlmAuthMode authMode, @Nullable String apiKey) {
        return probe(baseUrl, authMode, apiKey);
    }

    private LlmProbeResultDTO probe(String baseUrl, LlmAuthMode authMode, @Nullable String apiKey) {
        String url = stripTrailingSlash(baseUrl) + "/models";
        try {
            return restClient
                    .get()
                    .uri(url)
                    .headers(headers -> {
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                        if (StringUtils.hasText(apiKey)) {
                            if (authMode == LlmAuthMode.API_KEY) {
                                headers.set("api-key", apiKey);
                            } else {
                                headers.setBearerAuth(apiKey);
                            }
                        }
                    })
                    .exchange((clientRequest, clientResponse) -> {
                        int status = clientResponse.getStatusCode().value();
                        if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                            return LlmProbeResultDTO.unreachable(
                                    status, "The provider answered with HTTP " + status + ".");
                        }
                        byte[] response = clientResponse.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (response.length > MAX_RESPONSE_BYTES) {
                            return LlmProbeResultDTO.unreachable(status, "The provider’s response was too large.");
                        }
                        JsonNode body = objectMapper.readTree(response);
                        return LlmProbeResultDTO.reachable(extractModelIds(body), status);
                    });
        } catch (Exception e) {
            // The exception message may carry host detail, so keep it out of the response.
            log.info("LLM connection probe failed: reason={}", e.getClass().getSimpleName());
            return LlmProbeResultDTO.unreachable(
                    null,
                    "Hephaestus could not reach the provider (" + e.getClass().getSimpleName() + ").");
        }
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
