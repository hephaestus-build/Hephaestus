package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.GITHUB_API_BASE_URL;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExempt;
import de.tum.cit.aet.hephaestus.integration.core.egress.EgressExemption;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationSuspendedException;
import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

/**
 * Service for generating GitHub App JWT tokens and installation access tokens.
 * <p>
 * <b>Why REST API instead of GraphQL:</b>
 * GitHub App authentication (JWT generation and installation token minting) is only
 * available via REST API. The GraphQL API requires an already-minted token, so the
 * bootstrap process must use REST. This is a GitHub API limitation.
 * <p>
 * Endpoints used:
 * <ul>
 *   <li>{@code POST /app/installations/{installation_id}/access_tokens} - Mint installation token</li>
 * </ul>
 *
 * @see <a href="https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app">GitHub Docs - Installation Access Tokens</a>
 */
@Service
@EgressExempt(EgressExemption.OAUTH_CONTROL_PLANE)
public class GitHubAppTokenService {

    private final long appId;
    private final PrivateKey privateKey;
    private final boolean credentialsConfigured;
    private final WebClient webClient;

    // Cache installation tokens to avoid re-minting on every call.
    private final Cache<Long, CachedToken> installTokenCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(55))
            .maximumSize(10000)
            .build();

    // Track suspended installations to fail fast without calling GitHub.
    // This is an in-memory set that all threads see immediately, bypassing DB transaction isolation.
    private final Set<Long> suspendedInstallations = ConcurrentHashMap.newKeySet();

    public GitHubAppTokenService(GitHubProperties gitHubProperties) {
        long appId = gitHubProperties.app().id();
        Resource privateKeyRes = gitHubProperties.app().privateKeyLocation();
        String privateKeyPem = gitHubProperties.app().privateKey();
        this.appId = appId;
        this.credentialsConfigured = isKeyMaterialPresent(appId, privateKeyRes, privateKeyPem);
        this.privateKey = credentialsConfigured ? loadKey(privateKeyRes, privateKeyPem) : generateEphemeralRsaKey();
        HttpClient httpClient = HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Build a short-lived App JWT.
     * - iat is backdated by 60s to tolerate small clock skew between systems.
     * - exp is set to 9 minutes to stay within the 10-minute GitHub limit with buffer.
     */
    public String generateAppJWT() {
        if (!isConfigured()) {
            throw new IllegalStateException("GitHub App credentials not configured.");
        }
        Instant now = Instant.now();
        Algorithm algorithm = Algorithm.RSA256(null, (RSAPrivateKey) privateKey);
        return JWT.create()
                .withIssuer(String.valueOf(appId))
                .withIssuedAt(Date.from(now.minusSeconds(60)))
                .withExpiresAt(Date.from(now.plusSeconds(9 * 60)))
                .sign(algorithm);
    }

    /**
     * Return a valid installation access token for the given installation.
     * Uses the cached token if it exists and has >60s of validity left; otherwise refreshes.
     */
    public String getInstallationToken(long installationId) {
        return getInstallationTokenDetails(installationId).token();
    }

    /**
     * Alias for getInstallationToken - used by GitHubInstallationRepositoryEnumerationService.
     */
    public String getOrRefreshToken(long installationId) {
        return getInstallationToken(installationId);
    }

    /**
     * Return a valid installation token along with its expiry so callers can proactively refresh clients.
     */
    public InstallationToken getInstallationTokenDetails(long installationId) {
        CachedToken resolved = resolveToken(installationId);
        return new InstallationToken(resolved.token, resolved.expiresAt);
    }

    /**
     * Mint a fresh installation token via REST API.
     *
     * @throws InstallationNotFoundException if the installation no longer exists (404 from GitHub)
     * @throws IllegalStateException if the installation is marked as suspended
     */
    private CachedToken fetchTokenWithExpiry(Long installationId) {
        // Fail fast for suspended installations - don't waste API calls
        if (suspendedInstallations.contains(installationId)) {
            throw new InstallationSuspendedException(installationId);
        }

        try {
            String appJwt = generateAppJWT();

            InstallationTokenResponse response = webClient
                    .post()
                    .uri("/app/installations/{installationId}/access_tokens", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                    .retrieve()
                    .bodyToMono(InstallationTokenResponse.class)
                    .block();

            if (response == null || response.token() == null) {
                throw new IllegalStateException("Empty token response from GitHub for installation " + installationId);
            }

            return new CachedToken(response.token(), response.expiresAt());
        } catch (WebClientResponseException.NotFound e) {
            // Installation was deleted or is inaccessible.
            // NOTE: Do NOT call invalidate() here - this method is used as a cache loader
            // and Caffeine does not allow recursive cache modifications during compute operations.
            // The cache will not store the result when an exception is thrown.
            // Invalidation happens in resolveToken() after the exception propagates out.
            throw new InstallationNotFoundException(installationId, e);
        } catch (WebClientResponseException.Forbidden e) {
            // 403 means suspended - mark it and fail fast
            suspendedInstallations.add(installationId);
            installTokenCache.invalidate(installationId);
            throw new InstallationSuspendedException(installationId, e);
        } catch (InstallationNotFoundException e) {
            // Re-throw without wrapping
            throw e;
        } catch (Exception e) {
            throw new UncheckedIOException(
                    new IOException("GitHub error minting installation token for " + installationId, e));
        }
    }

    /**
     * Evict a cached installation token. Call this when the installation is known to be deleted.
     */
    public void evictInstallationToken(long installationId) {
        installTokenCache.invalidate(installationId);
    }

    /**
     * Mark an installation as suspended. Token minting will fail fast for suspended installations.
     * Call this when receiving a suspend event to immediately block all threads from hitting GitHub.
     */
    public void markInstallationSuspended(long installationId) {
        suspendedInstallations.add(installationId);
        installTokenCache.invalidate(installationId); // Also evict any cached token
    }

    /**
     * Mark an installation as active (not suspended). Token minting will proceed normally.
     * Call this when receiving an unsuspend event or when installation is created.
     */
    public void markInstallationActive(long installationId) {
        suspendedInstallations.remove(installationId);
    }

    /**
     * Check if an installation is marked as suspended in memory.
     */
    public boolean isInstallationMarkedSuspended(long installationId) {
        return suspendedInstallations.contains(installationId);
    }

    private CachedToken resolveToken(long installationId) {
        CachedToken cached = installTokenCache.getIfPresent(installationId);
        if (cached != null && cached.expiresAt.isAfter(Instant.now().plusSeconds(60))) {
            return cached;
        }

        CachedToken fresh;
        try {
            fresh = installTokenCache.get(installationId, this::fetchTokenWithExpiry);
        } catch (InstallationNotFoundException e) {
            // Invalidate cache OUTSIDE the compute operation (safe here)
            installTokenCache.invalidate(installationId);
            throw e;
        }

        if (fresh == null) {
            throw new IllegalStateException("Failed to mint installation token for " + installationId);
        }

        if (fresh.expiresAt.isBefore(Instant.now().plusSeconds(30))) {
            fresh = fetchTokenWithExpiry(installationId);
            installTokenCache.put(installationId, fresh);
        }

        return fresh;
    }

    public boolean isConfigured() {
        return credentialsConfigured;
    }

    public long getConfiguredAppId() {
        return appId;
    }

    // Key loading helpers

    private static PrivateKey loadKey(@Nullable Resource privateKeyRes, @Nullable String privateKeyPem) {
        try {
            if (privateKeyPem != null && !privateKeyPem.isBlank()) {
                return RsaPrivateKeys.parse(privateKeyPem);
            }
            if (privateKeyRes != null && privateKeyRes.exists()) {
                String pemKey = new String(privateKeyRes.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return RsaPrivateKeys.parse(pemKey);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read private key", e);
        }
        throw new IllegalStateException("No GitHub App private key configured.");
    }

    private static boolean isKeyMaterialPresent(
            long appId, @Nullable Resource privateKeyRes, @Nullable String privateKeyPem) {
        if (appId <= 0) {
            return false;
        }
        if (privateKeyPem != null && !privateKeyPem.isBlank()) {
            return true;
        }
        if (privateKeyRes == null) {
            return false;
        }
        try {
            return privateKeyRes.exists() && privateKeyRes.contentLength() > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static PrivateKey generateEphemeralRsaKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair().getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate ephemeral RSA private key", e);
        }
    }

    // Installation Status Verification

    /**
     * Verify the current status of an installation via GitHub REST API.
     * Uses App JWT authentication (not installation token) so it works even for suspended installations.
     *
     * @param installationId the installation to check
     * @return true if the installation is currently suspended, false if active
     * @throws InstallationNotFoundException if the installation no longer exists
     */
    public boolean isInstallationSuspended(long installationId) {
        if (!isConfigured()) {
            throw new IllegalStateException("GitHub App credentials not configured.");
        }

        try {
            String appJwt = generateAppJWT();

            InstallationStatusResponse response = webClient
                    .get()
                    .uri("/app/installations/{installationId}", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                    .retrieve()
                    .bodyToMono(InstallationStatusResponse.class)
                    .block();

            if (response == null) {
                throw new IllegalStateException("Empty response from GitHub for installation " + installationId);
            }

            return response.suspendedAt() != null;
        } catch (WebClientResponseException.NotFound e) {
            throw new InstallationNotFoundException(installationId, e);
        } catch (InstallationNotFoundException e) {
            throw e;
        } catch (WebClientResponseException e) {
            // Other HTTP errors (403 Forbidden, 500 Internal Server Error, etc.)
            throw new UncheckedIOException(new IOException(
                    "GitHub API error checking installation status for " + installationId + ": " + e.getStatusCode(),
                    e));
        } catch (RuntimeException e) {
            // Network errors, timeouts, or other transient issues
            throw new UncheckedIOException(
                    new IOException("GitHub error checking installation status for " + installationId, e));
        }
    }

    /** Deletes an installation with the App JWT. A missing installation is already deleted. */
    public void deleteInstallation(long installationId) {
        if (!isConfigured()) {
            throw new IllegalStateException("GitHub App credentials not configured.");
        }
        try {
            String appJwt = generateAppJWT();
            webClient
                    .delete()
                    .uri("/app/installations/{installationId}", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            // Desired state already reached.
        } catch (WebClientResponseException e) {
            throw new UncheckedIOException(new IOException(
                    "GitHub API error deleting installation " + installationId + ": " + e.getStatusCode(), e));
        } catch (RuntimeException e) {
            throw new UncheckedIOException(new IOException("GitHub error deleting installation " + installationId, e));
        } finally {
            installTokenCache.invalidate(installationId);
            suspendedInstallations.remove(installationId);
        }
    }

    // DTOs

    public record InstallationToken(String token, Instant expiresAt) {}

    private record CachedToken(String token, Instant expiresAt) {}

    private record InstallationTokenResponse(
            String token, @JsonProperty("expires_at") Instant expiresAt) {}

    private record InstallationStatusResponse(
            Long id,
            @JsonProperty("suspended_at") Instant suspendedAt,
            @JsonProperty("suspended_by") Object suspendedBy) {}
}
