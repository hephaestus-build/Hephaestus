package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobPrecomputeUsage;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.job.PrecomputeKindTotal;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JobJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtInvalidException;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * <p>The token's scopes become its authorities, and {@link LlmProxySecurityConfig} decides which paths
 * each scope reaches. The path decides the routing: on {@code /internal/llm/precompute/{slot}/**} the
 * slot selects the model. {@code chat} is the review's own model and bills to the review. Every other
 * slot is the precompute model of that kind frozen on the job and bills to its own ledger row.
 *
 * <p>Defense-in-depth: rejects requests from non-private IPs, since only Docker-internal traffic
 * should reach these endpoints.
 */
public class JobTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JobTokenAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String RUNTIME_PATH = "/internal/llm/runtime/";
    static final String PRECOMPUTE_PATH = "/internal/llm/precompute/";

    /**
     * The request header in which the precompute runner names the practice whose script makes a call.
     * The proxy refuses a precompute call without it, so that every precompute call is split by practice.
     */
    public static final String PRECOMPUTE_PRACTICE_HEADER = "x-hephaestus-practice";

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
        Set<String> scopes = Set.of(WorkerJwtIssuer.LLM_PROXY_SCOPE);
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
            scopes = jwt.scopes();
            if (!matchesRuntimeJob(request, jwt)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid runtime URL for token");
                return;
            }
            Optional<AgentJob> job = agentJobRepository.findByIdWithWorkspace(jwt.jobId());
            Optional<ConfigSnapshot> snapshot = runningSnapshot(jwt, job);
            if (snapshot.isPresent() && request.getRequestURI().startsWith(PRECOMPUTE_PATH)) {
                ModelKind slot = precomputeSlot(request.getRequestURI());
                if (slot == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Not found");
                    return;
                }
                routing = precomputeRouting(job.orElseThrow(), snapshot.get(), slot);
                if (routing.isEmpty()) {
                    response.sendError(
                            HttpServletResponse.SC_NOT_FOUND, "No " + slot.slot() + " model is bound for this job");
                    return;
                }
            } else {
                routing = snapshot.map(frozen -> reviewRouting(job.orElseThrow(), frozen));
            }
            if (routing.isEmpty() && isResultUpload(request, jwt) && isOwnedByAnotherWorker(jwt, job)) {
                response.sendError(HttpServletResponse.SC_CONFLICT, "Job is owned by another worker");
                return;
            }
        }
        if (routing.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(new JobTokenAuthentication(routing.get(), scopes));
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isOwnedByAnotherWorker(JobJwt jwt, Optional<AgentJob> optionalJob) {
        return optionalJob
                .filter(job -> job.getStatus() == AgentJobStatus.RUNNING
                        && job.getWorkspace().getId().equals(jwt.workspaceId())
                        && job.getRetryCount() == jwt.attempt())
                .map(job -> job.getWorkerId() != null && !workerId.equals(job.getWorkerId()))
                .orElse(false);
    }

    private static boolean isResultUpload(HttpServletRequest request, JobJwt jwt) {
        return "POST".equals(request.getMethod())
                && request.getRequestURI().equals(RUNTIME_PATH + jwt.jobId() + "/result");
    }

    private static boolean matchesRuntimeJob(HttpServletRequest request, JobJwt jwt) {
        String path = request.getRequestURI();
        if (!path.startsWith(RUNTIME_PATH)) {
            return true;
        }
        int end = path.indexOf('/', RUNTIME_PATH.length());
        String pathJobId = path.substring(RUNTIME_PATH.length(), end < 0 ? path.length() : end);
        return pathJobId.equals(jwt.jobId().toString());
    }

    /** The job's frozen snapshot, when the token names the attempt that this worker runs. */
    private Optional<ConfigSnapshot> runningSnapshot(JobJwt jwt, Optional<AgentJob> optionalJob) {
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
        try {
            return Optional.of(ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper));
        } catch (RuntimeException e) {
            log.warn("Failed to parse config snapshot for job {}: {}", job.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private ProxyRouting reviewRouting(AgentJob job, ConfigSnapshot snapshot) {
        return routing(
                "job:" + job.getId(),
                job,
                snapshot.model(),
                LlmUsageSourceType.AGENT_JOB,
                spentSoFarUsd(job, snapshot),
                0L,
                null);
    }

    /**
     * Routes a precompute call to the model of its slot. Its in-flight spend also counts the attempt's
     * other precompute models, because none of them is in the ledger before the attempt ends. Only spend
     * from the same purse counts, since each purse has its own cap.
     *
     * @return empty when no model of that kind is bound for this job
     */
    private Optional<ProxyRouting> precomputeRouting(AgentJob job, ConfigSnapshot snapshot, ModelKind slot) {
        FrozenModel model = slot == ModelKind.CHAT ? snapshot.model() : snapshot.precomputeSlot(slot);
        if (model == null) {
            return Optional.empty();
        }
        List<PrecomputeKindTotal> used = agentJobRepository.sumPrecomputeUsageByKind(
                job.getWorkspace().getId(), job.getId(), job.getRetryCount());
        return Optional.of(routing(
                "job:" + job.getId() + ":precompute",
                job,
                model,
                AgentJobPrecomputeUsage.ledgerSourceType(slot),
                precomputeSpentUsd(job, snapshot, used, model.connectionScope()),
                used.stream()
                        .mapToLong(total -> total.inputTokens() + total.outputTokens())
                        .sum(),
                slot));
    }

    private static ProxyRouting routing(
            String principal,
            AgentJob job,
            FrozenModel model,
            LlmUsageSourceType sourceType,
            BigDecimal spentUsd,
            long precomputeTokens,
            @Nullable ModelKind slot) {
        return new ProxyRouting(
                principal,
                model.apiProtocol(),
                model.baseUrl(),
                model.connectionScope(),
                model.connectionId(),
                model.modelId(),
                job.getWorkspace().getId(),
                new ProxyRouting.BilledAttempt(
                        sourceType, job.getId(), job.getRetryCount(), spentUsd, job.getWorkerId(), precomputeTokens),
                slot,
                slot == null ? null : model.dataHandlingTier());
    }

    /** Priced per kind like the ledger rows that the attempt ends with, not once per practice. */
    private static BigDecimal precomputeSpentUsd(
            AgentJob job, ConfigSnapshot snapshot, List<PrecomputeKindTotal> used, @Nullable FundingSource purse) {
        BigDecimal spent =
                Objects.equals(snapshot.connectionScope(), purse) ? spentSoFarUsd(job, snapshot) : BigDecimal.ZERO;
        for (PrecomputeKindTotal total : used) {
            FrozenModel model = snapshot.precomputeSlot(total.modelKind());
            LlmPriceSnapshot price = model == null ? null : model.priceSnapshot();
            if (model == null || price == null || !Objects.equals(model.connectionScope(), purse)) {
                continue;
            }
            BigDecimal cost = price.calculateCost(total.inputTokens(), total.outputTokens(), 0L, 0L)
                    .usd();
            if (cost != null) {
                spent = spent.add(cost);
            }
        }
        return spent;
    }

    /** The slot named by {@code /internal/llm/precompute/{slot}/...}, or {@code null} for no known slot. */
    static @Nullable ModelKind precomputeSlot(String path) {
        String rest = path.substring(PRECOMPUTE_PATH.length());
        int end = rest.indexOf('/');
        if (end <= 0) {
            return null;
        }
        String slot = rest.substring(0, end);
        return Arrays.stream(ModelKind.values())
                .filter(kind -> kind.slot().equals(slot))
                .findFirst()
                .orElse(null);
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
