package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants.GITLAB_GRAPHQL_PATH;

import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.CircuitBreakerOpenException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabCredentialHealthFilter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlRequest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Creates authenticated GitLab GraphQL clients from the guarded base client. */
@Component
@Slf4j
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabGraphQlClientProvider {

    /**
     * WebClient request attribute key for passing scopeId through to exchange filters.
     * This attribute is internal to the HTTP client pipeline — never sent on the wire.
     */
    public static final String SCOPE_ID_ATTRIBUTE = "hephaestus.integration.gitlab.scopeId";

    private final GitLabCredentialHealthFilter credentialHealth;
    private final HttpGraphQlClient baseClient;
    private final GitLabTokenService tokenService;
    private final CircuitBreaker circuitBreaker;
    private final GitLabRateLimitTracker rateLimitTracker;
    private final SilentModeGraphQlClientFactory clientFactory;

    public GitLabGraphQlClientProvider(
            @Qualifier("gitLabGraphQlClient") HttpGraphQlClient gitLabGraphQlClient,
            GitLabTokenService tokenService,
            @Qualifier("gitlabGraphQlCircuitBreaker") CircuitBreaker circuitBreaker,
            GitLabRateLimitTracker rateLimitTracker,
            SilentModeGraphQlClientFactory clientFactory,
            GitLabCredentialHealthFilter credentialHealth) {
        this.credentialHealth = credentialHealth;
        this.baseClient = gitLabGraphQlClient;
        this.tokenService = tokenService;
        this.circuitBreaker = circuitBreaker;
        this.rateLimitTracker = rateLimitTracker;
        this.clientFactory = clientFactory;
    }

    /**
     * Checks if the circuit breaker allows calls to GitLab.
     *
     * @return true if circuit is closed or half-open, false if open
     */
    public boolean isCircuitClosed() {
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN;
    }

    public CircuitBreaker.State getCircuitState() {
        return circuitBreaker.getState();
    }

    /** Records a successful API call for the circuit breaker. */
    public void recordSuccess() {
        circuitBreaker.onSuccess(0, TimeUnit.MILLISECONDS);
    }

    /** Records a failed API call for the circuit breaker. */
    public void recordFailure(Throwable throwable) {
        circuitBreaker.onError(0, TimeUnit.MILLISECONDS, throwable);
    }

    /**
     * Checks if the circuit breaker permits a call, throwing if not.
     *
     * @throws CircuitBreakerOpenException if the circuit is open
     */
    public void acquirePermission() {
        try {
            circuitBreaker.acquirePermission();
        } catch (CallNotPermittedException e) {
            log.warn("Rejected GitLab GraphQL call: reason=circuitBreakerOpen, state={}", circuitBreaker.getState());
            throw new CircuitBreakerOpenException("GitLab GraphQL API circuit breaker is open", e);
        }
    }

    /**
     * Returns an authenticated HttpGraphQlClient for the given scope.
     * <p>
     * The client is created by cloning the base client and setting:
     * <ul>
     *   <li>The scope's GitLab server URL + {@code /api/graphql} path</li>
     *   <li>The scope's PAT as a Bearer token in the Authorization header</li>
     * </ul>
     *
     * @param scopeId the workspace/scope ID
     * @return authenticated HttpGraphQlClient ready for use
     * @throws IllegalStateException if scope is not active or has no token
     */
    public HttpGraphQlClient forScope(Long scopeId) {
        String token = tokenService.getAccessToken(scopeId);
        String serverUrl = tokenService.resolveServerUrl(scopeId);

        return clientFactory.withBearerTokenAndAttribute(
                baseClient,
                serverUrl + GITLAB_GRAPHQL_PATH,
                token,
                SCOPE_ID_ATTRIBUTE,
                scopeId,
                new GraphQlClientInterceptor() {
                    @Override
                    public Mono<ClientGraphQlResponse> intercept(ClientGraphQlRequest request, Chain chain) {
                        return chain.next(request).flatMap(response -> {
                            boolean refused = response.getErrors().stream().anyMatch(error -> {
                                Object type = error.getExtensions().get("type");
                                if (type == null) type = error.getExtensions().get("code");
                                return "UNAUTHENTICATED".equals(type) || "UNAUTHORIZED".equals(type);
                            });
                            if (!refused
                                    && (!response.isValid()
                                            || !response.getErrors().isEmpty())) return Mono.just(response);
                            return credentialHealth
                                    .observe(scopeId, token, refused)
                                    .thenReturn(response);
                        });
                    }
                });
    }

    /**
     * Returns an authenticated HttpGraphQlClient using the provided token and server URL.
     * <p>
     * Use this method when you already have a valid token (e.g., from a cached context).
     *
     * @param token     the PAT
     * @param serverUrl the GitLab server base URL (e.g., {@code https://gitlab.com})
     * @return authenticated HttpGraphQlClient
     */
    public HttpGraphQlClient withToken(String token, String serverUrl) {
        return clientFactory.withBearerToken(baseClient, serverUrl + GITLAB_GRAPHQL_PATH, token);
    }

    /**
     * Updates the rate limit tracker from HTTP response headers.
     * <p>
     * Call this after every GraphQL query to keep rate limit tracking current.
     *
     * @param scopeId the scope that made the API call
     * @param headers the HTTP response headers
     */
    public void updateRateLimit(Long scopeId, @Nullable HttpHeaders headers) {
        rateLimitTracker.updateFromHeaders(scopeId, headers);
    }

    public GitLabRateLimitTracker getRateLimitTracker() {
        return rateLimitTracker;
    }

    /**
     * Waits if the rate limit is critically low for a scope.
     *
     * @return true if waited, false if no waiting was needed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean waitIfRateLimitLow(Long scopeId) throws InterruptedException {
        return rateLimitTracker.waitIfNeeded(scopeId);
    }

    /** Checks if the rate limit is critically low. */
    public boolean isRateLimitCritical(Long scopeId) {
        return rateLimitTracker.isCritical(scopeId);
    }

    public int getRateLimitRemaining(Long scopeId) {
        return rateLimitTracker.getRemaining(scopeId);
    }

    public @Nullable Instant getRateLimitResetAt(Long scopeId) {
        return rateLimitTracker.getResetAt(scopeId);
    }
}
