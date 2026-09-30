package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Manages GitLab Personal Access Token retrieval and validation.
 *
 * <p>This service encapsulates all GitLab-specific token logic:
 * <ul>
 *   <li>PAT retrieval from workspace via {@link InstallationTokenProvider}</li>
 *   <li>Token validation against {@code /api/v4/user} endpoint</li>
 *   <li>Server URL resolution (custom for self-hosted, default for gitlab.com)</li>
 *   <li>Validation result caching via Caffeine</li>
 * </ul>
 *
 * <p>Unlike {@link de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService}
 * which mints short-lived installation tokens from JWTs, this service works with
 * long-lived PATs that only need periodic validation.
 *
 * @see <a href="https://docs.gitlab.com/ee/api/rest/#personalprojectgroup-access-tokens">GitLab API Authentication</a>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabTokenService {

    private static final String USER_ENDPOINT = "/api/v4/user";

    private final InstallationTokenProvider tokenProvider;
    private final WebClient validationWebClient;
    private final GitLabProperties properties;
    private final Cache<Long, ValidatedToken> validationCache;

    public GitLabTokenService(
            InstallationTokenProvider tokenProvider, WebClient.Builder webClientBuilder, GitLabProperties properties) {
        this.tokenProvider = tokenProvider;
        this.validationWebClient = webClientBuilder.build();
        this.properties = properties;
        this.validationCache = Caffeine.newBuilder()
                .expireAfterWrite(properties.tokenValidationCacheDuration())
                .maximumSize(1000)
                .build();
    }

    /**
     * Gets the Personal Access Token for a scope.
     *
     * @param scopeId the workspace/scope ID
     * @return the PAT
     * @throws IllegalStateException if scope is not active or has no token
     */
    public String getAccessToken(Long scopeId) {
        if (!tokenProvider.isScopeActive(scopeId)) {
            throw new IllegalStateException(
                    "Scope " + scopeId + " is not active (suspended or purged). Refusing to provide token.");
        }

        return tokenProvider
                .getPersonalAccessToken(scopeId)
                .filter(t -> !t.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Scope " + scopeId + " is configured for GitLab PAT access but no token is stored."));
    }

    /**
     * Resolves the GitLab server URL for a scope.
     * <p>
     * Returns the workspace's custom server URL if set (for self-hosted instances),
     * otherwise falls back to the configured default (typically {@code https://gitlab.com}).
     *
     * @param scopeId the workspace/scope ID
     * @return the server URL (never null, never blank)
     */
    public String resolveServerUrl(Long scopeId) {
        return tokenProvider
                .getServerUrl(scopeId)
                .filter(url -> !url.isBlank())
                .map(GitLabTokenService::stripTrailingSlash)
                .orElse(properties.defaultServerUrl());
    }

    /**
     * Validates the PAT for a scope against the GitLab REST API.
     * <p>
     * Makes a {@code GET /api/v4/user} call with the token to verify it is valid.
     * Results are cached for the configured duration to avoid excessive validation calls.
     *
     * @param scopeId the workspace/scope ID
     * @return the validated token info, or null if validation fails
     */
    @Nullable
    public ValidatedToken validateToken(Long scopeId) {
        ValidatedToken cached = validationCache.getIfPresent(scopeId);
        if (cached != null) {
            return cached;
        }

        String token;
        try {
            token = getAccessToken(scopeId);
        } catch (IllegalStateException e) {
            log.warn("Cannot validate token for scope {}: {}", scopeId, e.getMessage());
            return null;
        }

        String serverUrl = resolveServerUrl(scopeId);

        try {
            GitLabUserResponse user = validationWebClient
                    .get()
                    .uri(serverUrl + USER_ENDPOINT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, scopeId)
                    .retrieve()
                    .bodyToMono(GitLabUserResponse.class)
                    .block(Duration.ofSeconds(10));

            if (user != null) {
                ValidatedToken validated = new ValidatedToken(token, user.id(), user.username(), Instant.now());
                validationCache.put(scopeId, validated);
                log.info(
                        "GitLab token validated: scopeId={}, username={}, userId={}",
                        scopeId,
                        user.username(),
                        user.id());
                return validated;
            }
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                log.warn("GitLab token invalid for scope {}: status={}", scopeId, status.value());
            } else {
                log.error(
                        "GitLab token validation failed for scope {}: status={}, message={}",
                        scopeId,
                        status.value(),
                        e.getMessage());
            }
        } catch (Exception e) {
            log.error("GitLab token validation error for scope {}: {}", scopeId, e.getMessage());
        }

        return null;
    }

    /**
     * Checks if the token for a scope is valid (uses cache).
     */
    public boolean isTokenValid(Long scopeId) {
        return validateToken(scopeId) != null;
    }

    /**
     * Invalidates cached validation for a scope.
     * Call when a token is known to be rotated or revoked.
     */
    public void invalidateCache(Long scopeId) {
        validationCache.invalidate(scopeId);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Validated token information cached after successful {@code /api/v4/user} call.
     *
     * @param token       the personal access token
     * @param gitlabUserId the GitLab user ID (numeric)
     * @param username     the GitLab username
     * @param validatedAt  when the token was last validated
     */
    public record ValidatedToken(String token, long gitlabUserId, String username, Instant validatedAt) {
        /** Excludes the token value to prevent accidental credential exposure in logs. */
        @Override
        public String toString() {
            return ("ValidatedToken[gitlabUserId=" + gitlabUserId
                    + ", username="
                    + username
                    + ", validatedAt="
                    + validatedAt
                    + "]");
        }
    }

    /**
     * Minimal response from GitLab {@code GET /api/v4/user} endpoint.
     * Only includes fields needed for token validation.
     */
    record GitLabUserResponse(long id, String username) {}
}
