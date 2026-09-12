package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JobJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtInvalidException;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security filter that authenticates requests to the LLM proxy using proxy-scoped bearer
 * tokens: an {@code AgentJob}'s job token, or a mentor session's registry-minted token (the mentor's
 * interactive sandbox is not an {@code AgentJob} row).
 *
 * <p>Defense-in-depth: rejects requests from non-private IPs, since only Docker-internal traffic
 * should reach these endpoints.
 */
public class JobTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JobTokenAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LLM_PROXY_SCOPE = "llm_proxy";

    private final AgentJobRepository agentJobRepository;
    private final WorkerJwtVerifier jwtVerifier;
    private final MentorProxyCredentialRegistry mentorRegistry;
    private final ObjectMapper objectMapper;
    private final String workerId;

    JobTokenAuthenticationFilter(
            AgentJobRepository agentJobRepository,
            WorkerJwtVerifier jwtVerifier,
            MentorProxyCredentialRegistry mentorRegistry,
            ObjectMapper objectMapper,
            String workerId) {
        this.agentJobRepository = agentJobRepository;
        this.jwtVerifier = jwtVerifier;
        this.mentorRegistry = mentorRegistry;
        this.objectMapper = objectMapper;
        this.workerId = workerId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isPrivateIp(request.getRemoteAddr())) {
            log.warn("LLM proxy request from non-private IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
            return;
        }

        String token = extractProxyToken(request);
        if (token == null || token.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing bearer token");
            return;
        }

        Optional<ProxyRouting> routing = mentorRegistry.validate(token);
        if (routing.isEmpty()) {
            JobJwt jwt;
            try {
                if (!(jwtVerifier.verify(token) instanceof JobJwt jobJwt)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                    return;
                }
                jwt = jobJwt;
            } catch (WorkerJwtInvalidException e) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                return;
            }
            if (!jwt.scopes().contains(LLM_PROXY_SCOPE)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient token scope");
                return;
            }
            routing = resolveJobRouting(jwt);
        }
        if (routing.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(new JobTokenAuthentication(routing.get()));
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<ProxyRouting> resolveJobRouting(JobJwt jwt) {
        Optional<AgentJob> optionalJob = agentJobRepository.findByIdWithWorkspace(jwt.jobId());
        if (optionalJob.isEmpty()) {
            return Optional.empty();
        }
        AgentJob job = optionalJob.get();
        if (!workerId.equals(job.getWorkerId())
                || job.getStatus() != AgentJobStatus.RUNNING
                || !job.getWorkspace().getId().equals(jwt.workspaceId())
                || job.getRetryCount() != jwt.attempt()) {
            return Optional.empty();
        }
        if (job.getConfigSnapshot() == null) {
            log.warn("RUNNING job {} has no config snapshot — cannot route proxy request", job.getId());
            return Optional.empty();
        }
        ConfigSnapshot snapshot;
        try {
            snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        } catch (RuntimeException e) {
            log.warn("Failed to parse config snapshot for job {}: {}", job.getId(), e.getMessage());
            return Optional.empty();
        }
        return Optional.of(new ProxyRouting(
                "job:" + job.getId(),
                snapshot.apiProtocol(),
                snapshot.baseUrl(),
                snapshot.connectionScope(),
                snapshot.connectionId(),
                snapshot.modelId(),
                job.getWorkspace().getId(),
                new ProxyRouting.BilledAttempt(
                        LlmUsageSourceType.AGENT_JOB,
                        job.getId(),
                        job.getRetryCount(),
                        spentSoFarUsd(job, snapshot),
                        job.getWorkerId())));
    }

    /**
     * Priced at the rates frozen onto the row at admission, so what the gate judges an attempt on
     * cannot drift from what the ledger will charge it. Reasoning tokens are deliberately absent from
     * the arguments: they are already counted inside the output bucket.
     */
    private static BigDecimal spentSoFarUsd(AgentJob job, ConfigSnapshot snapshot) {
        LlmPriceSnapshot price = snapshot.priceSnapshot();
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal cost = price.calculateCost(
                        zeroIfNull(job.getLlmTotalInputTokens()),
                        zeroIfNull(job.getLlmTotalOutputTokens()),
                        zeroIfNull(job.getLlmCacheReadTokens()),
                        zeroIfNull(job.getLlmCacheWriteTokens()))
                .usd();
        return cost != null ? cost : BigDecimal.ZERO;
    }

    private static long zeroIfNull(@Nullable Integer tokens) {
        return tokens != null ? tokens : 0L;
    }

    /**
     * {@code Authorization: Bearer} is the only shape accepted: also honouring provider-key headers
     * would blur the sandbox's trust boundary with the upstream provider's.
     */
    private @Nullable String extractProxyToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String bearer = auth.substring(BEARER_PREFIX.length()).trim();
            if (!bearer.isBlank()) {
                return bearer;
            }
        }

        return null;
    }

    /** Matches numeric IPv4 or IPv6 address literals (rejects hostnames to avoid DNS resolution). */
    private static final Pattern IP_LITERAL_PATTERN = Pattern.compile("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$|^[0-9a-fA-F:]+$");

    static boolean isPrivateIp(@Nullable String ip) {
        if (ip == null || !IP_LITERAL_PATTERN.matcher(ip).matches()) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
