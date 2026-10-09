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

/**
 * Caps how fast one authenticated sandbox may call the gateway. The bucket is keyed by the principal
 * {@link JobTokenAuthenticationFilter} resolved — one agent job or one Heph turn — so no
 * sandbox can drain another's budget, and no workspace's Heph traffic can throttle another's. Requests the
 * {@code exempt} matcher selects pass unlimited.
 *
 * <p>Runs after authentication and decides nothing about it: a request that carries neither a
 * {@link ProxyRouting} principal nor a {@link TurnEndpointAuthentication} is passed on for the chain's
 * authorization rules to answer, which is what keeps every response on this connector consistent.
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
        String principal;
        if (authentication != null && authentication.getPrincipal() instanceof ProxyRouting routing) {
            principal = routing.principalDescription();
        } else if (authentication instanceof TurnEndpointAuthentication turn) {
            principal = turn.getPrincipal();
        } else {
            chain.doFilter(request, response);
            return;
        }

        // The principal names a job or a turn, never a token — safe at WARN.
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
