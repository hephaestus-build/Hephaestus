package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * REST client for GitLab Personal Access Token self-inspection and rotation.
 *
 * <p>Uses the GitLab {@code /personal_access_tokens/self} endpoints to check
 * token expiry and rotate tokens before they expire. The self-rotation API
 * immediately revokes the old token and returns a new one.
 *
 * <p><b>Token family security:</b> GitLab tracks token families. If a revoked
 * token is used to attempt another rotation, GitLab revokes <em>all</em> tokens in the family.
 * This makes it critical to persist the new token immediately after rotation.
 *
 * @see <a href="https://docs.gitlab.com/ee/api/personal_access_tokens.html#rotate-a-personal-access-token">GitLab PAT Rotation</a>
 */
@Service
@EgressExempt(EgressExemption.OAUTH_CONTROL_PLANE)
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabTokenRotationClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabTokenRotationClient.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String SELF_TOKEN_ENDPOINT = "/api/v4/personal_access_tokens/self";
    private static final String SELF_TOKEN_ROTATE_ENDPOINT = "/api/v4/personal_access_tokens/self/rotate";

    private final GitLabTokenService tokenService;
    private final WebClient webClient;

    public GitLabTokenRotationClient(GitLabTokenService tokenService, WebClient.Builder webClientBuilder) {
        this.tokenService = tokenService;
        this.webClient = webClientBuilder.build();
    }

    private record ScopeCredentials(String serverUrl, String token) {
        @Override
        public String toString() {
            return "ScopeCredentials[serverUrl=" + serverUrl + "]";
        }
    }

    private ScopeCredentials resolveCredentials(Long scopeId) {
        return new ScopeCredentials(tokenService.resolveServerUrl(scopeId), tokenService.getAccessToken(scopeId));
    }

    /**
     * Retrieves information about the PAT used for the given scope.
     *
     * @param scopeId the workspace/scope ID
     * @return token info including expiry date
     */
    public TokenInfo getTokenInfo(Long scopeId) {
        ScopeCredentials creds = resolveCredentials(scopeId);

        Map<String, Object> response = webClient
                .get()
                .uri(creds.serverUrl() + SELF_TOKEN_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + creds.token())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(REQUEST_TIMEOUT);

        if (response == null) {
            throw new IllegalStateException("Empty response from GitLab token self-introspection");
        }

        Number idValue = (Number) Objects.requireNonNull(response).get("id");
        if (idValue == null) {
            throw new IllegalStateException(
                    "GitLab token introspection response missing 'id' field: scopeId=" + scopeId);
        }
        long id = idValue.longValue();
        String name = (String) Objects.requireNonNull(response).get("name");
        if (!response.containsKey("expires_at")) {
            throw new IllegalStateException("GitLab token introspection did not report expiry: scopeId=" + scopeId);
        }
        String expiresAtStr = (String) Objects.requireNonNull(response).get("expires_at");
        LocalDate expiresAt = expiresAtStr != null ? LocalDate.parse(expiresAtStr) : null;

        return new TokenInfo(id, name, expiresAt);
    }

    /**
     * Rotates the PAT used for the given scope.
     *
     * <p><b>WARNING:</b> The old token is <em>immediately</em> revoked. The caller
     * must persist the returned token value before making any further API calls.
     *
     * @param scopeId   the workspace/scope ID
     * @param expiresAt new expiry date for the rotated token
     * @return the new token value and expiry date
     */
    public RotatedToken rotateToken(Long scopeId, LocalDate expiresAt) {
        ScopeCredentials creds = resolveCredentials(scopeId);

        Map<String, Object> response = webClient
                .post()
                .uri(creds.serverUrl() + SELF_TOKEN_ROTATE_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + creds.token())
                .bodyValue(Map.of("expires_at", expiresAt.toString()))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(REQUEST_TIMEOUT);

        if (response == null) {
            throw new IllegalStateException("Empty response from GitLab token rotation");
        }

        String newToken = (String) Objects.requireNonNull(response).get("token");
        if (newToken == null || newToken.isBlank()) {
            throw new IllegalStateException(
                    "GitLab rotation response missing 'token' field. Old token is revoked. Manual intervention required: scopeId="
                            + scopeId);
        }
        String newExpiresAtStr = (String) Objects.requireNonNull(response).get("expires_at");
        LocalDate newExpiresAt = newExpiresAtStr != null ? LocalDate.parse(newExpiresAtStr) : expiresAt;

        log.info("Rotated GitLab PAT: scopeId={}, newExpiresAt={}", scopeId, newExpiresAt);
        return new RotatedToken(newToken, newExpiresAt);
    }

    /**
     * Token self-inspection result.
     *
     * @param id        the token ID
     * @param name      the token name
     * @param expiresAt expiry date (null if no expiry)
     */
    public record TokenInfo(
            long id, @Nullable String name, @Nullable LocalDate expiresAt) {}

    /**
     * Result of a token rotation.
     *
     * @param token     the new token value (the old token is now revoked)
     * @param expiresAt the new expiry date
     */
    public record RotatedToken(String token, LocalDate expiresAt) {
        @Override
        public String toString() {
            return "RotatedToken[expiresAt=" + expiresAt + "]";
        }
    }
}
