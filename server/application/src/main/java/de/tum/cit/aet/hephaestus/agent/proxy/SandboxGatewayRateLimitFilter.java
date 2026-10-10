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
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Caps how fast one authenticated sandbox may call the gateway. The bucket is keyed by the principal
 * {@link JobTokenAuthenticationFilter} resolved — one agent job or one mentor session — so no
 * sandbox can drain another's budget, and no workspace's mentor traffic can throttle another's.
 *
 * <p>Runs after the chain's authorization rules, so a call that its scope refuses spends nothing
 * from the bucket of the principal that its path routes to. A request that carries no
 * {@link ProxyRouting} principal is passed on unchanged.
 *
 * <p>A bucket store it cannot reach fails open. The caller is already job-token-authenticated and its
 * spend still has to pass the budget gate, so an unreachable store must not close the sandbox's only
 * door mid-review; it is counted instead, so a limiter that has stopped limiting is visible rather
 * than silent.
 */
public final class SandboxGatewayRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SandboxGatewayRateLimitFilter.class);

    /** Namespaced so a gateway bucket can never collide with an auth bucket in a shared store. */
    private static final String KEY_PREFIX = "sandbox-gateway:";

    private final BucketConfiguration bucketConfiguration;
    private final BucketResolver bucketResolver;
    private final ObjectMapper objectMapper;
    private final ProxyAccounting accounting;

    SandboxGatewayRateLimitFilter(
            AuthRateLimitProperties.Limit limit,
            BucketResolver bucketResolver,
            ObjectMapper objectMapper,
            ProxyAccounting accounting) {
        this.bucketConfiguration = limit.bucketConfiguration();
        this.bucketResolver = bucketResolver;
        this.objectMapper = objectMapper;
        this.accounting = accounting;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ProxyRouting routing)) {
            chain.doFilter(request, response);
            return;
        }

        // The principal names a job or a session, never a token — safe at WARN.
        String principal = routing.principalDescription();
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
        accounting.recordGatewayThrottled();
        RateLimitResponse.writeTooManyRequests(request, response, retryAfterSeconds, objectMapper);
    }
}
