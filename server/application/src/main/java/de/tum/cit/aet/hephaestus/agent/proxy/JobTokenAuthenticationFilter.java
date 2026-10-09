package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.JobJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwt;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtInvalidException;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security filter that authenticates requests on the sandbox gateway with proxy-scoped bearer tokens:
 *
 * <ul>
 *   <li>an {@code AgentJob}'s {@link JobJwt}, for the review's model calls and its sandbox runtime;
 *   <li>a Heph turn attempt's {@link TurnJwt}, for the turn's model calls and its turn endpoints. A model call
 *       holds only while this worker holds the attempt's lease. A turn endpoint reads no row here: it checks that
 *       the attempt runs on this worker, and its writes are conditional on the lease;
 *   <li>the random token the Heph host's gateway session was registered with, which no {@code agent_job} row
 *       backs. It reads only that session's own runtime paths and never reaches a model.
 * </ul>
 *
 * <p>Defense-in-depth: rejects requests from non-private IPs, since only Docker-internal traffic
 * should reach these endpoints.
 */
public class JobTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JobTokenAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LLM_PROXY_SCOPE = "llm_proxy";
    private static final String RUNTIME_PATH = "/internal/llm/runtime/";
    private static final String TURN_PATH = "/internal/llm/turn/";

    private final AgentJobRepository agentJobRepository;
    private final MentorTurnRepository mentorTurnRepository;
    private final SandboxGatewaySessions gatewaySessions;
    private final WorkerJwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;
    private final String workerId;

    JobTokenAuthenticationFilter(
            AgentJobRepository agentJobRepository,
            MentorTurnRepository mentorTurnRepository,
            SandboxGatewaySessions gatewaySessions,
            WorkerJwtVerifier jwtVerifier,
            ObjectMapper objectMapper,
            String workerId) {
        this.agentJobRepository = agentJobRepository;
        this.mentorTurnRepository = mentorTurnRepository;
        this.gatewaySessions = gatewaySessions;
        this.jwtVerifier = jwtVerifier;
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

        if (!isSignedToken(token)) {
            authenticateGatewaySession(request, response, filterChain, token);
            return;
        }
        WorkerJwt jwt;
        try {
            jwt = jwtVerifier.verify(token);
        } catch (WorkerJwtInvalidException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
            return;
        }
        JobTokenAuthentication authentication;
        switch (jwt) {
            case JobJwt job -> {
                if (!job.scopes().contains(LLM_PROXY_SCOPE)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient token scope");
                    return;
                }
                if (!matchesRuntimeJob(request, job)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid runtime URL for token");
                    return;
                }
                Optional<AgentJob> agentJob = agentJobRepository.findByIdWithWorkspace(job.jobId());
                Optional<ProxyRouting> routing = resolveJobRouting(job, agentJob);
                if (routing.isEmpty()) {
                    if (isResultUpload(request, job) && isOwnedByAnotherWorker(job, agentJob)) {
                        response.sendError(HttpServletResponse.SC_CONFLICT, "Job is owned by another worker");
                    } else {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                    }
                    return;
                }
                authentication = new JobTokenAuthentication(routing.get(), null);
            }
            case TurnJwt turn -> {
                if (!turn.scopes().contains(LLM_PROXY_SCOPE)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient token scope");
                    return;
                }
                if (!matchesTurn(request, turn)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid turn URL for token");
                    return;
                }
                if (request.getRequestURI().startsWith(TURN_PATH)) {
                    // The turn endpoints check that the attempt runs on this worker, which reads no row.
                    authenticated(request, response, filterChain, new TurnEndpointAuthentication(turn));
                    return;
                }
                Optional<ProxyRouting> routing = resolveTurnRouting(turn);
                if (routing.isEmpty()) {
                    // The attempt ended or moved on: the host stops working on it.
                    response.sendError(HttpServletResponse.SC_CONFLICT, "This turn attempt no longer holds its lease");
                    return;
                }
                authentication = new JobTokenAuthentication(routing.get(), turn);
            }
            default -> {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                return;
            }
        }

        authenticated(request, response, filterChain, authentication);
    }

    private static void authenticated(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            AbstractAuthenticationToken authentication)
            throws ServletException, IOException {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** A signed worker token has three dot-separated parts; a gateway session's random token has none. */
    private static boolean isSignedToken(String token) {
        return token.chars().filter(c -> c == '.').count() == 2;
    }

    /** The session's runtime paths are discovery, workspace and the command channel. */
    private void authenticateGatewaySession(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain, String token)
            throws ServletException, IOException {
        Optional<UUID> session = gatewaySessions.sessionFor(token);
        String path = request.getRequestURI();
        if (session.isEmpty()
                || !"GET".equals(request.getMethod())
                || !(path.equals(RUNTIME_PATH + session.get())
                        || path.startsWith(RUNTIME_PATH + session.get() + "/"))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
            return;
        }
        authenticated(request, response, filterChain, new GatewaySessionAuthentication(session.get()));
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
        if (path.startsWith(TURN_PATH)) {
            return false;
        }
        if (!path.startsWith(RUNTIME_PATH)) {
            return true;
        }
        int end = path.indexOf('/', RUNTIME_PATH.length());
        String pathJobId = path.substring(RUNTIME_PATH.length(), end < 0 ? path.length() : end);
        return pathJobId.equals(jwt.jobId().toString());
    }

    /** A turn credential reaches the model and its own turn endpoints, never a sandbox runtime. */
    private static boolean matchesTurn(HttpServletRequest request, TurnJwt jwt) {
        String path = request.getRequestURI();
        if (path.startsWith(RUNTIME_PATH)) {
            return false;
        }
        return !path.startsWith(TURN_PATH) || path.startsWith(TURN_PATH + jwt.turnId() + "/");
    }

    /**
     * The attempt's routing while this worker holds its lease ({@link MentorTurn#isLeased}): the route frozen at
     * admission, and what the attempt has spent so far, priced at the admitted rates.
     */
    private Optional<ProxyRouting> resolveTurnRouting(TurnJwt jwt) {
        Optional<MentorTurn> found = mentorTurnRepository.findByIdAndWorkspaceId(jwt.turnId(), jwt.workspaceId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MentorTurn turn = found.get();
        if (!turn.isLeased()
                || turn.getAttempt() != jwt.attempt()
                || !workerId.equals(turn.getWorkerId())
                || !turn.getConversationId().equals(jwt.conversationId())) {
            return Optional.empty();
        }
        return MentorTurnAdmission.read(turn.getAdmission())
                .map(MentorTurnAdmission::route)
                .map(route -> new ProxyRouting(
                        "mentor-turn:" + turn.getId(),
                        route.apiProtocol(),
                        route.baseUrl(),
                        route.connectionScope(),
                        route.connectionId(),
                        route.modelId(),
                        turn.getWorkspaceId(),
                        new ProxyRouting.BilledAttempt(
                                LlmUsageSourceType.MENTOR_TURN,
                                turn.getId(),
                                turn.getAttempt(),
                                spentSoFarUsd(turn),
                                workerId)));
    }

    /** Priced at the rates frozen onto the turn at admission, like a job's in-flight spend. */
    private static BigDecimal spentSoFarUsd(MentorTurn turn) {
        BigDecimal cost = MentorTurnAdmission.priceOf(turn.getAdmission())
                .calculateCost(
                        turn.getInputTokens(),
                        turn.getOutputTokens(),
                        turn.getCachedTokens(),
                        turn.getCacheWriteTokens())
                .usd();
        return cost != null ? cost : BigDecimal.ZERO;
    }

    private Optional<ProxyRouting> resolveJobRouting(JobJwt jwt, Optional<AgentJob> optionalJob) {
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
                        workerId)));
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
