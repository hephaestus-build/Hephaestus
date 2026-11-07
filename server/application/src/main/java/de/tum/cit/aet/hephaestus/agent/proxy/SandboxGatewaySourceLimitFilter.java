package de.tum.cit.aet.hephaestus.agent.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.RateLimitResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounds the requests of one TCP peer before any credential is read, so a caller without a valid token, or one that
 * floods with a valid token, costs no token verification and no lease read past its budget. A refusal is counted, never
 * logged: a log line for each refusal would be the flood itself.
 *
 * <p>The buckets stay in this process in every role. A shared store would cost a database row lock before the caller
 * proves anything, and this is flood protection for one worker, not a quota. They are limiter state, not a cache of a
 * slow read.
 */
final class SandboxGatewaySourceLimitFilter extends OncePerRequestFilter {

    /** Far above the sandboxes of one worker; bounds what a pathological stream of peers can hold. */
    private static final int MAX_SOURCES = 10_000;

    private final Bandwidth bandwidth;
    private final ObjectMapper objectMapper;
    private final ProxyAccounting accounting;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_SOURCES)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    SandboxGatewaySourceLimitFilter(
            AuthRateLimitProperties.Limit limit, ObjectMapper objectMapper, ProxyAccounting accounting) {
        this.bandwidth = limit.bucketConfiguration().getBandwidths()[0];
        this.objectMapper = objectMapper;
        this.accounting = accounting;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Every request on the gateway connector carries it; a request without it shares one budget, not its own.
        String source = Objects.toString(request.getAttribute(SandboxGatewayConnector.PEER_ATTRIBUTE), "unknown");
        Bucket bucket = buckets.get(
                source, ignored -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        accounting.gatewayRefused(SandboxGatewayRefusal.SOURCE_LIMIT);
        RateLimitResponse.writeTooManyRequests(
                request, response, RateLimitResponse.retryAfterSeconds(probe), objectMapper);
    }
}
