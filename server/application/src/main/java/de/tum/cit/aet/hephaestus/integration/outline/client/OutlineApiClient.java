package de.tum.cit.aet.hephaestus.integration.outline.client;

import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineApiKey;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineAuth;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineCollectionModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineDocumentModel;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineNavigationNode;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineTeam;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineUser;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineWebhookSubscription;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineApiClient implements OutlineTokenClient, OutlineContentClient, OutlineWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(OutlineApiClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final int PAGE_LIMIT = 100;

    private static final int MAX_PAGES = 1000;

    // Envelope shapes, one per call. Explicit ParameterizedTypeReferences because the {data,pagination}
    // wrapper is generic — the generated models describe only the inner data payload.
    private static final ParameterizedTypeReference<OutlineEnvelope<OutlineAuth>> AUTH_INFO =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<List<OutlineApiKey>>> API_KEY_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<List<OutlineCollectionModel>>> COLLECTION_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<List<OutlineNavigationNode>>> COLLECTION_DOCUMENTS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<List<OutlineDocumentModel>>> DOCUMENT_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<OutlineDocumentModel>> DOCUMENT_INFO =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<String>> EXPORT =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<List<OutlineWebhookSubscription>>>
            WEBHOOK_SUBSCRIPTION_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<OutlineWebhookSubscription>> WEBHOOK_SUBSCRIPTION =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<OutlineEnvelope<Void>> EMPTY =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final OutlineOriginPolicy originPolicy;

    @Autowired
    public OutlineApiClient(
            @Qualifier("outlineRestApiCircuitBreaker") CircuitBreaker circuitBreaker,
            @Qualifier("outlineRestApiRetry") Retry retry,
            @Qualifier("outlineWebClient") WebClient webClient,
            OutlineOriginPolicy originPolicy) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.originPolicy = originPolicy;
    }

    /** Identity from Outline's {@code auth.info}. The team id is stable per instance and becomes the Connection's instance key. */
    public record OutlineIdentity(
            String teamId,
            @Nullable String teamName,
            @Nullable String userId) {}

    /**
     * Validates a token via {@code auth.info}, returning the resolved identity. Throws
     * {@link OutlineApiException} on an unreachable host, a rejected token, or a response without a team.
     */
    @Override
    public OutlineIdentity validateToken(String serverUrl, String token) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        OutlineEnvelope<OutlineAuth> response = post(resolvedUrl, token, "/api/auth.info", Map.of(), AUTH_INFO);
        if (response == null
                || response.data() == null
                || response.data().getTeam() == null
                || response.data().getTeam().getId() == null) {
            throw new OutlineApiException("Outline auth.info returned no team for this token");
        }
        OutlineTeam team = response.data().getTeam();
        OutlineUser user = response.data().getUser();
        return new OutlineIdentity(
                Objects.requireNonNull(team.getId()), team.getName(), user == null ? null : user.getId());
    }

    public record OutlineTokenDescription(
            @Nullable String name,
            @Nullable String last4,
            @Nullable Instant expiresAt,
            @Nullable Instant lastActiveAt) {}

    /**
     * Describes the token via {@code apiKeys.list}, matched on the {@code last4} suffix. Empty when the key
     * is absent or {@code apiKeys.list} is out of scope — such a token still works for content sync, so
     * missing metadata must not surface as an error.
     */
    @Override
    public Optional<OutlineTokenDescription> describeToken(String serverUrl, String token) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        OutlineEnvelope<List<OutlineApiKey>> response;
        try {
            response = post(resolvedUrl, token, "/api/apiKeys.list", Map.of("limit", PAGE_LIMIT), API_KEY_LIST);
        } catch (OutlineApiException e) {
            if (isForbidden(e)) {
                log.debug("Outline apiKeys.list is not in this token's scope — token metadata unavailable");
                return Optional.empty();
            }
            throw e;
        }
        if (response == null || response.data() == null || token.length() < 4) {
            return Optional.empty();
        }
        String suffix = token.substring(token.length() - 4);
        return response.data().stream()
                .filter(key -> suffix.equals(key.getLast4()))
                .findFirst()
                .map(key -> new OutlineTokenDescription(
                        key.getName(), key.getLast4(), key.getExpiresAt(), key.getLastActiveAt()));
    }

    /** Lists the collections the token can see ({@code collections.list}); the catalog pass refreshes mirrored-collection metadata. */
    @Override
    public List<OutlineCollectionModel> listCollections(String serverUrl, String token) {
        return listCollections(serverUrl, token, MAX_PAGES);
    }

    /**
     * {@link #listCollections(String, String)} under an explicit page cap — interactive admin paths pass a
     * small cap so a pathological instance cannot stall a request thread; a hit cap logs and returns partial.
     */
    @Override
    public List<OutlineCollectionModel> listCollections(String serverUrl, String token, int maxPages) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        List<OutlineCollectionModel> all = new ArrayList<>();
        for (int page = 0, offset = 0; page < maxPages; page++, offset += PAGE_LIMIT) {
            OutlineEnvelope<List<OutlineCollectionModel>> body = post(
                    resolvedUrl,
                    token,
                    "/api/collections.list",
                    Map.of("offset", offset, "limit", PAGE_LIMIT),
                    COLLECTION_LIST);
            List<OutlineCollectionModel> pageData = body == null ? null : body.data();
            if (pageData == null || pageData.isEmpty()) {
                return all;
            }
            all.addAll(pageData);
            if (pageData.size() < PAGE_LIMIT) {
                return all;
            }
        }
        log.info(
                "outline.client: collections.list stopped at the {}-page cap ({} collections) for {} — result may be truncated",
                maxPages,
                all.size(),
                resolvedUrl);
        return all;
    }

    /**
     * Fetches a collection's document tree ({@code collections.documents}); {@code children} carry the
     * nesting the sync flattens into parent relationships.
     */
    @Override
    public List<OutlineNavigationNode> listCollectionDocuments(String serverUrl, String token, String collectionId) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        OutlineEnvelope<List<OutlineNavigationNode>> body = post(
                resolvedUrl, token, "/api/collections.documents", Map.of("id", collectionId), COLLECTION_DOCUMENTS);
        List<OutlineNavigationNode> data = body == null ? null : body.data();
        return data == null ? List.of() : data;
    }

    /**
     * Lists per-document metadata ({@code documents.list}, newest-{@code updatedAt} first). Ordering matters:
     * the sync spends its bounded export budget front-to-back, and {@code updatedAt} is the incremental cursor.
     */
    @Override
    public List<OutlineDocumentModel> listDocuments(String serverUrl, String token, String collectionId) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        List<OutlineDocumentModel> all = new ArrayList<>();
        for (int page = 0, offset = 0; page < MAX_PAGES; page++, offset += PAGE_LIMIT) {
            OutlineEnvelope<List<OutlineDocumentModel>> body = post(
                    resolvedUrl,
                    token,
                    "/api/documents.list",
                    Map.of(
                            "collectionId",
                            collectionId,
                            "offset",
                            offset,
                            "limit",
                            PAGE_LIMIT,
                            "sort",
                            "updatedAt",
                            "direction",
                            "DESC"),
                    DOCUMENT_LIST);
            List<OutlineDocumentModel> pageData = requirePage(body, "documents.list", collectionId);
            if (pageData.isEmpty()) {
                return all;
            }
            all.addAll(pageData);
            if (pageData.size() < PAGE_LIMIT) {
                return all;
            }
        }
        throw pageCapExhausted("documents.list", collectionId, all.size());
    }

    /**
     * Fetches one document's metadata ({@code documents.info}); empty on HTTP 404 (the webhook refresh treats
     * that as a tombstone), rethrows every other failure.
     */
    @Override
    public Optional<OutlineDocumentModel> getDocumentInfo(String serverUrl, String token, String documentId) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        OutlineEnvelope<OutlineDocumentModel> body;
        try {
            body = post(resolvedUrl, token, "/api/documents.info", Map.of("id", documentId), DOCUMENT_INFO);
        } catch (OutlineApiException e) {
            if (isNotFound(e)) {
                return Optional.empty();
            }
            throw e;
        }
        return Optional.ofNullable(body == null ? null : body.data());
    }

    /**
     * Lists a collection's ARCHIVED documents ({@code documents.list} with {@code statusFilter:["archived"]}).
     * Outline's default listing excludes archived documents, so without this call the tombstone-by-absence
     * sweep would wipe an archived (soft-deleted, recoverable) document as a permanent delete.
     */
    @Override
    public List<OutlineDocumentModel> listArchivedDocuments(String serverUrl, String token, String collectionId) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        List<OutlineDocumentModel> all = new ArrayList<>();
        for (int page = 0, offset = 0; page < MAX_PAGES; page++, offset += PAGE_LIMIT) {
            OutlineEnvelope<List<OutlineDocumentModel>> body = post(
                    resolvedUrl,
                    token,
                    "/api/documents.list",
                    Map.of(
                            "collectionId",
                            collectionId,
                            "offset",
                            offset,
                            "limit",
                            PAGE_LIMIT,
                            "sort",
                            "updatedAt",
                            "direction",
                            "DESC",
                            "statusFilter",
                            List.of("archived")),
                    DOCUMENT_LIST);
            List<OutlineDocumentModel> pageData = requirePage(body, "documents.list[archived]", collectionId);
            if (pageData.isEmpty()) {
                return all;
            }
            all.addAll(pageData);
            if (pageData.size() < PAGE_LIMIT) {
                return all;
            }
        }
        throw pageCapExhausted("documents.list[archived]", collectionId, all.size());
    }

    /**
     * The page's rows, or {@link OutlineApiException} when Outline answered without a {@code data} array.
     *
     * <p>Data-loss guard: a short read treated as end-of-listing becomes the reconcile's {@code seen} set, and
     * the tombstone-by-absence sweep then deletes every mirrored document past the truncated tail. Failing the
     * call skips the sweep, leaving the mirror intact.
     */
    private static List<OutlineDocumentModel> requirePage(
            OutlineEnvelope<List<OutlineDocumentModel>> body, String call, String collectionId) {
        List<OutlineDocumentModel> pageData = body == null ? null : body.data();
        if (pageData == null) {
            throw new OutlineApiException("Outline " + call
                    + " returned no data for collection "
                    + collectionId
                    + " — refusing to treat a malformed page as the end of the listing");
        }
        return pageData;
    }

    /** Same contract as {@link #requirePage}: an enumeration that hits the page cap is truncated, i.e. wrong. */
    private static OutlineApiException pageCapExhausted(String call, String collectionId, int collected) {
        return new OutlineApiException("Outline " + call
                + " exceeded the "
                + MAX_PAGES
                + "-page cap for collection "
                + collectionId
                + " after "
                + collected
                + " documents — refusing to sync a truncated listing");
    }

    /**
     * Lists the change-notification subscriptions the token owns ({@code webhookSubscriptions.list}). The
     * registrar's self-heal pass diffs its stored subscription id against this.
     */
    @Override
    public List<OutlineWebhookSubscription> listWebhookSubscriptions(String serverUrl, String token) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        List<OutlineWebhookSubscription> all = new ArrayList<>();
        for (int page = 0, offset = 0; page < MAX_PAGES; page++, offset += PAGE_LIMIT) {
            OutlineEnvelope<List<OutlineWebhookSubscription>> body = post(
                    resolvedUrl,
                    token,
                    "/api/webhookSubscriptions.list",
                    Map.of("offset", offset, "limit", PAGE_LIMIT),
                    WEBHOOK_SUBSCRIPTION_LIST);
            List<OutlineWebhookSubscription> pageData = body == null ? null : body.data();
            if (pageData == null || pageData.isEmpty()) {
                break;
            }
            all.addAll(pageData);
            if (pageData.size() < PAGE_LIMIT) {
                break;
            }
        }
        return all;
    }

    /** A permanent failure whose wire cause was an HTTP 404 — the addressed resource is gone upstream. */
    private static boolean isNotFound(OutlineApiException e) {
        return (e.getCause() instanceof WebClientResponseException wire
                && wire.getStatusCode().value() == 404);
    }

    /** Outline answers 403 both for an out-of-scope token and for a call the key's user may not make. */
    private static boolean isForbidden(OutlineApiException e) {
        return (e.getCause() instanceof WebClientResponseException wire
                && wire.getStatusCode().value() == 403);
    }

    /** Exports a document's body as Markdown ({@code documents.export}); {@code null} when Outline responds without a body. */
    @Override
    public @Nullable String exportDocument(String serverUrl, String token, String documentId) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        OutlineEnvelope<String> body =
                post(resolvedUrl, token, "/api/documents.export", Map.of("id", documentId), EXPORT);
        return body == null ? null : body.data();
    }

    /**
     * Creates a subscription ({@code webhookSubscriptions.create}) posting the given events to
     * {@code deliveryUrl}, signed with {@code signingSecret}; returns its id, or {@code null} when Outline responds without one.
     */
    @Override
    public @Nullable String createWebhookSubscription(
            String serverUrl,
            String token,
            String name,
            String deliveryUrl,
            String signingSecret,
            List<String> events) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        OutlineEnvelope<OutlineWebhookSubscription> body = post(
                resolvedUrl,
                token,
                "/api/webhookSubscriptions.create",
                Map.of("name", name, "url", deliveryUrl, "secret", signingSecret, "events", events),
                WEBHOOK_SUBSCRIPTION);
        return body == null || body.data() == null ? null : body.data().getId();
    }

    @Override
    public void deleteWebhookSubscription(String serverUrl, String token, String subscriptionId) {
        String resolvedUrl = resolveAndValidateServerUrl(serverUrl);
        try {
            post(resolvedUrl, token, "/api/webhookSubscriptions.delete", Map.of("id", subscriptionId), EMPTY);
        } catch (OutlineApiException e) {
            if (!isNotFound(e)) {
                throw e;
            }
        }
    }

    /** One {@code POST} through the retry decorator; each attempt goes through the circuit breaker so every failure counts toward the rate. */
    private <T> T post(
            String resolvedUrl,
            String token,
            String path,
            Object requestBody,
            ParameterizedTypeReference<T> responseType) {
        return retry.executeSupplier(() -> executeOnce(resolvedUrl, token, path, requestBody, responseType));
    }

    /**
     * A single attempt through the circuit breaker. Translates the raw wire failures: HTTP 429 →
     * {@link OutlineRateLimitedException} (with {@code Retry-After}), a 5xx / transport failure →
     * retryable {@link OutlineApiException}, a 4xx → permanent {@link OutlineApiException}, an open breaker
     * → a distinct permanent {@link OutlineApiException}.
     */
    private <T> T executeOnce(
            String resolvedUrl,
            String token,
            String path,
            Object requestBody,
            ParameterizedTypeReference<T> responseType) {
        Supplier<T> call = () -> webClient
                .post()
                .uri(resolvedUrl + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(responseType)
                .block(REQUEST_TIMEOUT);
        try {
            return circuitBreaker.executeSupplier(call);
        } catch (WebClientResponseException.TooManyRequests e) {
            throw new OutlineRateLimitedException(parseRetryAfter(e), e);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            log.atDebug()
                    .addKeyValue("event.name", "integration.request.failed")
                    .addKeyValue("integration.kind", "OUTLINE")
                    .addKeyValue("integration.operation", path)
                    .addKeyValue("server.address", URI.create(resolvedUrl).getHost())
                    .addKeyValue("http.response.status_code", status)
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Outline API request failed");
            throw new OutlineApiException(
                    "Outline " + path + " failed (HTTP " + status + ")",
                    e,
                    e.getStatusCode().is5xxServerError());
        } catch (CallNotPermittedException e) {
            throw new OutlineApiException("Outline API is temporarily unavailable (circuit open)", e);
        } catch (OutlineApiException e) {
            throw e;
        } catch (Exception e) {
            // Decoding and transport exception text can contain private response or request data.
            log.atDebug()
                    .addKeyValue("event.name", "integration.request.failed")
                    .addKeyValue("integration.kind", "OUTLINE")
                    .addKeyValue("integration.operation", path)
                    .addKeyValue("server.address", URI.create(resolvedUrl).getHost())
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Outline API request failed");
            throw new OutlineApiException("Could not reach the Outline server", e, /* retryable */ true);
        }
    }

    /** Reads the {@code Retry-After} header (delta-seconds) from a 429, or {@code null} when absent/unparseable. */
    private static @Nullable Duration parseRetryAfter(WebClientResponseException e) {
        String header = e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    private String resolveAndValidateServerUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new OutlineApiException("An Outline server URL is required");
        }
        String trimmed = serverUrl.trim();
        String normalized = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (!originPolicy.allows(normalized)) {
            throw new OutlineApiException("Outline origin is not approved by the instance operator");
        }
        return normalized;
    }
}
