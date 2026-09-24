package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Token-bucket rate limiter for sensitive and resource-intensive endpoints. Sits on the
 * resource-server chain (covers {@code /auth/refresh}, {@code /workspaces/{slug}/user-view/users/**},
 * {@code DELETE /user}) and the oauth2Login chain (covers {@code GET /oauth2/authorization/*}); registered after
 * authentication so the account principal is resolvable from the {@link SecurityContextHolder}.
 *
 * <p>On breach the response is HTTP 429 with an RFC 9457 {@code application/problem+json} body and a
 * {@code Retry-After} header (seconds until the bucket next has a token). Requests that match no
 * configured limit pass through untouched — in particular the worker-hub / webhook paths, which live
 * on a different chain and are additionally guarded here defensively.
 *
 * <p>Which endpoints are limited and how each is keyed is encoded by the {@link Endpoint} enum
 * (namespace + account-vs-IP scope) and applied in {@link #resolveBucketKey}. The namespace prefix
 * guarantees two endpoints never share a bucket even for the same principal; account-scoped limits
 * fall back to the client IP when the request reaches the filter unauthenticated.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private final AuthRateLimitProperties properties;
    private final BucketResolver bucketResolver;
    private final ObjectMapper objectMapper;
    private final AuthMetrics metrics;

    public AuthRateLimitFilter(
            AuthRateLimitProperties properties,
            BucketResolver bucketResolver,
            ObjectMapper objectMapper,
            AuthMetrics metrics) {
        this.properties = properties;
        this.bucketResolver = bucketResolver;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Identifies which configured limit (if any) applies to a request, and how to key it. */
    private enum Endpoint {
        OAUTH_AUTHORIZATION("oauth-authz", false, true),
        REFRESH("refresh", true, true),
        USER_VIEW("user-view", true, true),
        DELETE_USER("delete-user", true, true),
        // GDPR Art. 20 export: cap POST /user/exports (the async assembly). Account-scoped (JWT sub)
        // with IP fallback — the route requires isAuthenticated(), so sub is normally present.
        EXPORT("export", true, false),
        MENTOR_CHAT("mentor-chat", true, false),
        REVIEW_REQUEST("review-request", true, false),
        SYNC_TRIGGER("sync-trigger", true, false);

        private final String namespace;
        /** Whether the limit keys by account (with IP fallback) vs. always by IP. */
        private final boolean accountScoped;

        private final boolean failOpen;

        Endpoint(String namespace, boolean accountScoped, boolean failOpen) {
            this.namespace = namespace;
            this.accountScoped = accountScoped;
            this.failOpen = failOpen;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        Endpoint endpoint = match(request);
        if (endpoint == null) {
            chain.doFilter(request, response);
            return;
        }

        AuthRateLimitProperties.Limit limit = limitFor(endpoint);
        String key = resolveBucketKey(endpoint, request);
        ConsumptionProbe probe = bucketResolver.tryConsume(key, limit.bucketConfiguration(), e -> {
            metrics.recordRateLimitBackendError();
            log.warn("Rate limit backend error: endpoint={} key={}", endpoint, key, e);
        });
        if (probe == null) {
            if (endpoint.failOpen) {
                chain.doFilter(request, response);
            } else {
                writeServiceUnavailable(request, response);
            }
            return;
        }

        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = RateLimitResponse.retryAfterSeconds(probe);
        // key is namespaced (no raw account-id PII beyond what already exists in auth logs); safe at WARN.
        log.warn("Rate limit exceeded: endpoint={} key={} retryAfterSeconds={}", endpoint, key, retryAfterSeconds);
        // Tag by bucket namespace — bounded, no PII.
        metrics.recordRateLimitBlocked(endpoint.namespace);
        RateLimitResponse.writeTooManyRequests(request, response, retryAfterSeconds, objectMapper);
    }

    /**
     * Maps {@code (method, path)} to the configured limit, or {@code null} if none applies. Worker-hub
     * and webhook paths are explicitly excluded (defence in depth — they live on another chain).
     */
    @Nullable
    private static Endpoint match(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        if (path.startsWith("/api/workers/") || path.startsWith("/webhooks/") || path.startsWith("/actuator/")) {
            return null;
        }
        String method = request.getMethod();
        if ("GET".equals(method) && path.startsWith("/oauth2/authorization/")) {
            return Endpoint.OAUTH_AUTHORIZATION;
        }
        if ("POST".equals(method) && path.equals("/auth/refresh")) {
            return Endpoint.REFRESH;
        }
        // Keyed on the administrator, not the viewed user, so switching users does not reset the budget.
        if ("GET".equals(method) && path.matches("/workspaces/[^/]+/user-view/users(?:/.*)?")) {
            return Endpoint.USER_VIEW;
        }
        if ("DELETE".equals(method) && path.equals("/user")) {
            return Endpoint.DELETE_USER;
        }
        // GDPR export: cap ONLY the POST that starts an async full-bundle assembly (the expensive,
        // storage-amplifying op). The download is a cheap ownership+READY-gated blob read — rate-limiting
        // it with the same budget would penalise legitimate polling.
        if ("POST".equals(method) && path.equals("/user/exports")) {
            return Endpoint.EXPORT;
        }
        if ("POST".equals(method) && path.matches("/workspaces/[^/]+/mentor/chat")) {
            return Endpoint.MENTOR_CHAT;
        }
        if ("POST".equals(method) && path.matches("/workspaces/[^/]+/practices/review-requests")) {
            return Endpoint.REVIEW_REQUEST;
        }
        if ("POST".equals(method) && path.matches("/workspaces/[^/]+/connections/[^/]+/sync/jobs")) {
            return Endpoint.SYNC_TRIGGER;
        }
        return null;
    }

    private AuthRateLimitProperties.Limit limitFor(Endpoint endpoint) {
        return switch (endpoint) {
            case OAUTH_AUTHORIZATION -> properties.oauthAuthorization();
            case REFRESH -> properties.refresh();
            case USER_VIEW -> properties.userView();
            case DELETE_USER -> properties.deleteUser();
            case EXPORT -> properties.export();
            case MENTOR_CHAT -> properties.mentorChat();
            case REVIEW_REQUEST -> properties.reviewRequest();
            case SYNC_TRIGGER -> properties.syncTrigger();
        };
    }

    /**
     * Derives the bucket key. Account-scoped endpoints prefer the JWT {@code sub}; when the principal
     * is absent (request reached the filter before/without authentication) they fall back to the
     * client IP so an unauthenticated flood is still capped. The namespace prefix guarantees two
     * endpoints never share a bucket even for the same principal.
     */
    String resolveBucketKey(Endpoint endpoint, HttpServletRequest request) {
        if (endpoint.accountScoped) {
            Optional<String> sub = currentSubject();
            if (sub.isPresent()) {
                return endpoint.namespace + ":acct:" + sub.get();
            }
        }
        return endpoint.namespace + ":ip:" + clientIp(request);
    }

    private static Optional<String> currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            return Optional.ofNullable(jwt.getSubject());
        }
        return Optional.empty();
    }

    /**
     * Client IP for IP-keyed buckets, from {@code getRemoteAddr()} only — never a bespoke X-Forwarded-For
     * parse. In prod Tomcat's {@code RemoteIpValve} ({@code forward-headers-strategy: native}, trust pinned
     * by {@code ProxyTrustGuard}) has already validated XFF and rewritten {@code getRemoteAddr()} to the
     * unforgeable client address; a second parse would double-count hops or key off a spoofable entry. In
     * dev it is the direct socket peer. The container is the single source of truth for proxy trust.
     */
    String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }

    private void writeServiceUnavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Rate limiting is temporarily unavailable.");
        problem.setTitle("Service Unavailable");
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
