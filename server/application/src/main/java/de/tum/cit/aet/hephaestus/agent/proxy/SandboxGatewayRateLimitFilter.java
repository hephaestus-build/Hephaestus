package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.RateLimitResponse;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

// Authorize before consuming rate limits, and key buckets by principal to isolate sandboxes.
// An unavailable bucket store permits the call; authentication and budget checks still apply.
// The exempt requests are a Heph host's turn bookkeeping, which it sends several times a second.
public final class SandboxGatewayRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SandboxGatewayRateLimitFilter.class);

    /** Namespaced so a gateway bucket can never collide with an auth bucket in a shared store. */
    private static final String KEY_PREFIX = "sandbox-gateway:";

    private final BucketConfiguration bucketConfiguration;
    private final BucketResolver bucketResolver;
    private final ObjectMapper objectMapper;
    private final ProxyAccounting accounting;
    private final RequestMatcher exempt;

    SandboxGatewayRateLimitFilter(
            AuthRateLimitProperties.Limit limit,
            BucketResolver bucketResolver,
            ObjectMapper objectMapper,
            ProxyAccounting accounting,
            RequestMatcher exempt) {
        this.bucketConfiguration = limit.bucketConfiguration();
        this.bucketResolver = bucketResolver;
        this.objectMapper = objectMapper;
        this.accounting = accounting;
        this.exempt = exempt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return exempt.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ProxyIdentity identity)) {
            chain.doFilter(request, response);
            return;
        }

        // The principal names a job or a session, never a token — safe at WARN.
        String principal = identity.description();
        ConsumptionProbe probe = bucketResolver.tryConsume(KEY_PREFIX + principal, bucketConfiguration, e -> {
            accounting.recordGatewayLimiterError();
            log.warn("Sandbox gateway rate limit backend error: principal={}", principal, e);
        });
        if (probe == null || probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = RateLimitResponse.retryAfterSeconds(probe);
        log.warn(
                "Sandbox gateway rate limit exceeded: principal={} retryAfterSeconds={}", principal, retryAfterSeconds);
        accounting.gatewayRefused(SandboxGatewayRefusal.PRINCIPAL_LIMIT);
        RateLimitResponse.writeTooManyRequests(request, response, retryAfterSeconds, objectMapper);
    }
}
