package de.tum.cit.aet.hephaestus.integration.slack.connect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Slack {@code oauth.v2.access} client — exchanges an auth code for a bot token.
 * Throws {@link SlackOAuthException} on transport, non-2xx, or {@code ok=false};
 * secrets are never logged.
 */
@Component
@EgressExempt(EgressExemption.OAUTH_CONTROL_PLANE)
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(SlackOAuthClient.class);

    private static final String TOKEN_ENDPOINT = "/api/oauth.v2.access";

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public SlackOAuthClient(
            @Value("${hephaestus.integration.slack.client-id:}") String clientId,
            @Value("${hephaestus.integration.slack.client-secret:}") String clientSecret,
            @Value("${hephaestus.integration.slack.api-base:https://slack.com}") String apiBase,
            RestClient.Builder restClients) {
        this.clientId = clientId == null ? "" : clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.restClient = restClients.baseUrl(stripTrailingSlash(apiBase)).build();
    }

    /**
     * Exchange an authorisation {@code code} for an installation's bot token + team metadata.
     *
     * @throws SlackOAuthException on HTTP failure, malformed body, or {@code ok=false}.
     */
    public OAuthV2Access exchangeCode(String code, @Nullable String redirectUri) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new SlackOAuthException("slack oauth client not configured");
        }
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("code", code);
        if (redirectUri != null && !redirectUri.isBlank()) {
            body.add("redirect_uri", redirectUri);
        }

        OAuthV2Access response;
        try {
            response = restClient
                    .post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(OAuthV2Access.class);
        } catch (RestClientException e) {
            log.warn("Slack oauth.v2.access transport failure: {}", e.getClass().getSimpleName());
            throw new SlackOAuthException("transport_failure", e);
        }
        if (response == null) {
            throw new SlackOAuthException("empty_response");
        }
        if (!response.ok()) {
            String error = response.error() == null ? "unknown" : response.error();
            log.warn("Slack oauth.v2.access returned ok=false: error={}", error);
            throw new SlackOAuthException(error);
        }
        return response;
    }

    /**
     * Best-effort {@code auth.revoke} — invalidates the bot token on Slack's side so a
     * disconnect on our side doesn't leave a dangling credential. Returns true when Slack
     * confirms the revocation; false on transport/HTTP failure or {@code ok=false}.
     * Never throws — disconnect-from-vendor is fire-and-forget by design.
     */
    public boolean revoke(String botToken) {
        try {
            revokeStrict(botToken);
            return true;
        } catch (SlackOAuthException e) {
            log.warn("Slack auth.revoke failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    public void revokeStrict(String botToken) {
        if (botToken == null || botToken.isBlank()) {
            throw new SlackOAuthException("missing_token");
        }
        AuthResponse response;
        try {
            response = restClient
                    .post()
                    .uri("/api/auth.revoke")
                    .header("Authorization", "Bearer " + botToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve()
                    .body(AuthResponse.class);
        } catch (RestClientException e) {
            throw new SlackOAuthException("transport_failure", e);
        }
        if (response == null) {
            throw new SlackOAuthException("empty_response");
        }
        if (!response.ok() && !"token_revoked".equals(response.error())) {
            throw new SlackOAuthException(response.error() == null ? "unknown" : response.error());
        }
    }

    /**
     * The scopes that {@code botToken} holds. Slack names them in the {@code X-OAuth-Scopes} header of every Web API
     * response; {@code auth.test} needs no scope of its own.
     *
     * @throws SlackOAuthException with Slack's error, or with {@code scopes_not_reported} when Slack named no scopes:
     *     a missing header proves no missing scope
     */
    public Set<String> grantedScopes(String botToken) {
        ResponseEntity<AuthResponse> response;
        try {
            response = restClient
                    .post()
                    .uri("/api/auth.test")
                    .header("Authorization", "Bearer " + botToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve()
                    .toEntity(AuthResponse.class);
        } catch (RestClientException e) {
            throw new SlackOAuthException("transport_failure", e);
        }
        AuthResponse body = response.getBody();
        if (body == null) {
            throw new SlackOAuthException("empty_response");
        }
        if (!body.ok()) {
            throw new SlackOAuthException(body.error() == null ? "unknown" : body.error());
        }
        List<String> named = response.getHeaders().get("X-OAuth-Scopes");
        if (named == null) {
            throw new SlackOAuthException("scopes_not_reported");
        }
        return named.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(scope -> !scope.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The {@code ok} and {@code error} that {@code auth.revoke} and {@code auth.test} answer with. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthResponse(
            @JsonProperty("ok") boolean ok,
            @JsonProperty("error") @Nullable String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuthV2Access(
            @JsonProperty("ok") boolean ok,
            @JsonProperty("error") @Nullable String error,
            @JsonProperty("access_token") @Nullable String accessToken,
            @JsonProperty("team") @Nullable Team team,
            @JsonProperty("expires_in") @Nullable Integer expiresIn,
            @JsonProperty("refresh_token") @Nullable String refreshToken) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Team(
                @JsonProperty("id") @Nullable String id,
                @JsonProperty("name") @Nullable String name) {}
    }
}
