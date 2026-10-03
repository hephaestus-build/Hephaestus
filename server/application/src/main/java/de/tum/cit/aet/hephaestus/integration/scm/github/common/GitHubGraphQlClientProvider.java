package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.CircuitBreakerOpenException;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService.InstallationToken;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRateLimit;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Component;

/** Creates authenticated GitHub GraphQL clients from the guarded base client. */
@Component
@Slf4j
public class GitHubGraphQlClientProvider {

    private final HttpGraphQlClient baseClient;
    private final InstallationTokenProvider tokenProvider;
    private final GitHubAppTokenService appTokens;
    private final CircuitBreaker circuitBreaker;
    private final RateLimitTracker rateLimitTracker;
    private final GitHubRestRateLimitSeeder rateLimitSeeder;
    private final SilentModeGraphQlClientFactory clientFactory;

    public GitHubGraphQlClientProvider(
            HttpGraphQlClient gitHubGraphQlClient,
            InstallationTokenProvider tokenProvider,
            GitHubAppTokenService appTokens,
            @Qualifier("githubGraphQlCircuitBreaker") CircuitBreaker circuitBreaker,
            RateLimitTracker rateLimitTracker,
            GitHubRestRateLimitSeeder rateLimitSeeder,
            SilentModeGraphQlClientFactory clientFactory) {
        this.baseClient = gitHubGraphQlClient;
        this.tokenProvider = tokenProvider;
        this.appTokens = appTokens;
        this.circuitBreaker = circuitBreaker;
        this.rateLimitTracker = rateLimitTracker;
        this.rateLimitSeeder = rateLimitSeeder;
        this.clientFactory = clientFactory;
    }

    /**
     * Checks if the circuit breaker allows calls to GitHub.
     * <p>
     * Use this to preemptively check if sync operations should be attempted.
     *
     * @return true if circuit is closed or half-open, false if open
     */
    public boolean isCircuitClosed() {
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN;
    }

    public CircuitBreaker.State getCircuitState() {
        return circuitBreaker.getState();
    }

    /** Call after a successful GraphQL operation completes. */
    public void recordSuccess() {
        circuitBreaker.onSuccess(0, TimeUnit.MILLISECONDS);
    }

    /** Call when a GraphQL operation fails with a retryable error. */
    public void recordFailure(Throwable throwable) {
        circuitBreaker.onError(0, TimeUnit.MILLISECONDS, throwable);
    }

    /**
     * Call before making a GraphQL request to fail fast when the circuit is open.
     *
     * @throws CircuitBreakerOpenException if the circuit is open
     */
    public void acquirePermission() {
        try {
            circuitBreaker.acquirePermission();
        } catch (CallNotPermittedException e) {
            log.warn("Rejected GraphQL call: reason=circuitBreakerOpen, state={}", circuitBreaker.getState());
            throw new CircuitBreakerOpenException("GitHub GraphQL API circuit breaker is open", e);
        }
    }

    /**
     * Returns an authenticated HttpGraphQlClient for the given scope.
     * <p>
     * The client is created by cloning the base client and adding the appropriate
     * authentication header based on the scope's git provider mode:
     * <ul>
     * <li>GitHub App Installation: Uses short-lived installation tokens</li>
     * <li>Personal Access Token: Uses the stored PAT</li>
     * </ul>
     *
     * @param scopeId the scope ID to authenticate for
     * @return authenticated HttpGraphQlClient ready for use
     * @throws IllegalArgumentException if scope not found
     * @throws IllegalStateException    if scope has invalid authentication
     *                                  config
     */
    public HttpGraphQlClient forScope(Long scopeId) {
        String token = getToken(scopeId);
        // Learn this scope's real GraphQL ceiling from REST GET /rate_limit while it has nothing observed.
        // Fire-and-forget and self-throttled, so it costs the sync neither latency nor quota (GitHub
        // documents that endpoint as not counting against the limit) — see GitHubRestRateLimitSeeder.
        rateLimitSeeder.seedIfUnobserved(scopeId, token);
        return clientFactory.withBearerToken(baseClient, token);
    }

    /**
     * Returns an authenticated HttpGraphQlClient using the provided token.
     * <p>
     * Use this method when you already have a valid token (e.g., from a webhook
     * context
     * or cached installation token).
     *
     * @param token the OAuth/installation token
     * @return authenticated HttpGraphQlClient
     */
    public HttpGraphQlClient withToken(String token) {
        return clientFactory.withBearerToken(baseClient, token);
    }

    /**
     * Extracts rate limit data from the {@code rateLimit} field of a GraphQL response and updates the
     * tracker for the given scope. Call after every GraphQL query execution.
     *
     * @return the extracted rate limit info, or null if not present
     */
    @Nullable
    public GHRateLimit trackRateLimit(Long scopeId, @Nullable ClientGraphQlResponse response) {
        return rateLimitTracker.updateFromResponse(scopeId, response);
    }

    public RateLimitTracker getRateLimitTracker() {
        return rateLimitTracker;
    }

    /**
     * Call before making GraphQL requests in a loop to proactively avoid hitting the rate limit.
     *
     * @return true if the method waited, false if no waiting was needed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean waitIfRateLimitLow(Long scopeId) throws InterruptedException {
        return rateLimitTracker.waitIfNeeded(scopeId);
    }

    /** Use to decide whether to abort a sync operation early. */
    public boolean isRateLimitCritical(Long scopeId) {
        return rateLimitTracker.isCritical(scopeId);
    }

    public int getRateLimitRemaining(Long scopeId) {
        return rateLimitTracker.getRemaining(scopeId);
    }

    /** @return the reset instant, or null if unknown */
    public @Nullable Instant getRateLimitResetAt(Long scopeId) {
        return rateLimitTracker.getResetAt(scopeId);
    }

    public String getToken(Long scopeId) {
        // Fail fast for suspended/inactive scopes - don't waste API calls
        if (!tokenProvider.isScopeActive(scopeId)) {
            throw new IllegalStateException(
                    "Scope " + scopeId + " is not active (suspended or purged). Refusing to mint token.");
        }

        AuthMode authMode = tokenProvider.getAuthMode(scopeId);

        if (authMode == AuthMode.INSTALLATION_APP) {
            Long installationId = tokenProvider
                    .getInstallationId(scopeId)
                    .orElseThrow(() -> new IllegalStateException("Scope " + scopeId + " has no installation id."));
            InstallationToken token = appTokens.getInstallationTokenDetails(installationId);
            return token.token();
        }

        return tokenProvider
                .getPersonalAccessToken(scopeId)
                .filter(t -> !t.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "Scope " + scopeId + " is configured for PAT access but no token is stored."));
    }
}
