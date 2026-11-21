package de.tum.cit.aet.hephaestus.integration.scm.github.app.productfeedback;

import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues.Refused;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues.Unknown;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * The GitHub App's REST and GraphQL transport for {@link GitHubProductFeedbackIssues}, and the readers that refuse an
 * incomplete answer.
 */
@EgressExempt(EgressExemption.PRODUCT_FEEDBACK)
final class GitHubProductFeedbackApi {

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    private final GitHubAppTokenService tokens;
    private final OutboundEgressGuard egress;
    private final RestClient api;
    private final URI apiBase;

    GitHubProductFeedbackApi(
            GitHubAppTokenService tokens,
            OutboundEgressGuard egress,
            RestClient.Builder restClients,
            String apiBaseUrl) {
        this.tokens = tokens;
        this.egress = egress;
        this.apiBase = URI.create(apiBaseUrl);
        this.api = restClients
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(20))
                                .withRedirects(HttpRedirects.DONT_FOLLOW)))
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    String appJwt() {
        try {
            return tokens.generateAppJWT();
        } catch (RuntimeException failure) {
            throw new Unknown("GitHub App authentication is unavailable.", failure);
        }
    }

    /** Silent Mode holds every write; the caller retries later and reconciles by the marker first. */
    void allowWrite(String operation) {
        if (!egress.deliveryAllowed(operation)) throw new Unknown("Silent Mode holds product feedback writes.", null);
    }

    Optional<String> reachableInstallationToken(long installationId) {
        if (tokens.isInstallationMarkedSuspended(installationId)) return Optional.empty();
        try {
            return Optional.of(tokens.getInstallationToken(installationId));
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof WebClientResponseException response) {
                    if (response.getStatusCode().value() == 404) return Optional.empty();
                    throw httpFailure(response.getStatusCode().value(), response.getHeaders(), failure);
                }
            }
            throw new Unknown("GitHub installation authentication is unavailable.", failure);
        }
    }

    String installationToken(long installationId) {
        return reachableInstallationToken(installationId)
                .orElseThrow(() -> new Refused("The frozen GitHub installation is unavailable."));
    }

    Optional<JsonNode> getOptional(String path, String token) {
        var response = request(HttpMethod.GET, path, token, null, true);
        if (response.getStatusCode().value() == 404) return Optional.empty();
        return Optional.of(body(response));
    }

    ResponseEntity<JsonNode> request(HttpMethod method, String path, String token, @Nullable Object payload) {
        return request(method, path, token, payload, false);
    }

    ResponseEntity<JsonNode> request(
            HttpMethod method, String path, String token, @Nullable Object payload, boolean allowMissing) {
        try {
            var request = api.method(method).uri(checkedUri(path)).headers(headers -> headers.setBearerAuth(token));
            if (payload != null) request.contentType(MediaType.APPLICATION_JSON).body(payload);
            return request.exchange((outgoing, response) -> {
                int status = response.getStatusCode().value();
                if (allowMissing && status == 404)
                    return ResponseEntity.status(404).build();
                HttpHeaders headers = response.getHeaders();
                if (status < 200 || status >= 300) throw httpFailure(status, headers, null);
                return ResponseEntity.status(status).headers(headers).body(response.bodyTo(JsonNode.class));
            });
        } catch (RestClientException failure) {
            throw new Unknown("GitHub request outcome is unknown after a transport or response error.", failure);
        }
    }

    private static RuntimeException httpFailure(int status, HttpHeaders headers, @Nullable Throwable cause) {
        if (status == 429
                || (status == 403
                        && (headers.containsHeader(HttpHeaders.RETRY_AFTER)
                                || "0".equals(headers.getFirst("x-ratelimit-remaining"))))) {
            return new Unknown("GitHub rate limit reached. Retry at " + retryAt(headers) + ".", cause);
        }
        if (status >= 400 && status < 500) {
            var refused = new Refused("GitHub refused the request (HTTP " + status + ").");
            if (cause != null) refused.initCause(cause);
            return refused;
        }
        return new Unknown("GitHub request outcome is unknown (HTTP " + status + ").", cause);
    }

    JsonNode graphQl(String token, String query, Map<String, Object> variables, boolean allowMissingNodes) {
        var httpResponse = request(HttpMethod.POST, "/graphql", token, Map.of("query", query, "variables", variables));
        JsonNode response = body(httpResponse);
        JsonNode errors = response.path("errors");
        if (!errors.isMissingNode()) {
            for (JsonNode error : array(errors)) {
                String type = error.path("type").asString("");
                if (type.equals("RATE_LIMITED")) throw httpFailure(429, httpResponse.getHeaders(), null);
                if (allowMissingNodes
                        && type.equals("NOT_FOUND")
                        && error.path("path").path(0).asString("").equals("nodes")) continue;
                if (Set.of("FORBIDDEN", "UNPROCESSABLE", "NOT_FOUND", "GRAPHQL_VALIDATION_FAILED")
                        .contains(type)) {
                    throw new Refused("GitHub refused the GraphQL request (" + type + ").");
                }
                throw new Unknown("GitHub GraphQL request outcome is unknown.", null);
            }
        }
        JsonNode data = response.path("data");
        if (!data.isObject()) throw incomplete();
        return data;
    }

    private URI checkedUri(String path) {
        URI uri;
        try {
            uri = apiBase.resolve(path);
        } catch (IllegalArgumentException failure) {
            throw new Unknown("GitHub returned an invalid API link.", failure);
        }
        if (!Objects.equals(uri.getScheme(), apiBase.getScheme())
                || !Objects.equals(uri.getAuthority(), apiBase.getAuthority())
                || uri.getUserInfo() != null) {
            throw new Unknown("GitHub returned a pagination link outside its API.", null);
        }
        return uri;
    }

    @Nullable
    String nextPage(HttpHeaders headers, String currentPath) {
        for (String link : headers.getOrEmpty(HttpHeaders.LINK)) {
            var matcher = NEXT_LINK.matcher(link);
            if (matcher.find()) {
                URI next = checkedUri(matcher.group(1));
                String query = next.getRawQuery();
                if (query == null) throw incomplete();
                return checkedUri(currentPath).getRawPath() + "?" + query;
            }
            if (link.contains("rel=\"next\"")) throw incomplete();
        }
        return null;
    }

    private static Instant retryAt(HttpHeaders headers) {
        Instant now = Instant.now();
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        String reset = headers.getFirst("x-ratelimit-reset");
        try {
            if (retryAfter != null) {
                try {
                    return now.plusSeconds(Math.max(0, Long.parseLong(retryAfter)));
                } catch (NumberFormatException ignored) {
                    return ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
                }
            }
            if (reset != null) return Instant.ofEpochSecond(Long.parseLong(reset));
        } catch (DateTimeException | NumberFormatException | ArithmeticException ignored) {
            // A malformed header must not turn a rate limit into a permanent refusal.
        }
        return now.plusSeconds(60);
    }

    static JsonNode body(ResponseEntity<JsonNode> response) {
        JsonNode body = response.getBody();
        if (body == null || body.isNull()) throw incomplete();
        return body;
    }

    static JsonNode array(JsonNode node) {
        if (!node.isArray()) throw incomplete();
        return node;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) throw incomplete();
        return value.asString();
    }

    static @Nullable String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNull()) return null;
        return text(node, field);
    }

    static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) throw incomplete();
        return value.asLong();
    }

    static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) throw incomplete();
        return value.asBoolean();
    }

    static Unknown incomplete() {
        return new Unknown("GitHub returned an incomplete response.", null);
    }
}
