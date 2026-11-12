package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.hc.core5.net.InetAddressUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

public final class JobTokenAuthenticationFilter extends AuthenticationFilter {
    /**
     * The body of the {@code 409} for an attempt that no longer runs. No provider sends it, so a runner that reads it
     * knows its attempt ended rather than that a provider refused a call; {@code pi-model-failure.ts} holds the same.
     */
    public static final String ATTEMPT_ENDED = "This attempt no longer holds its lease.";

    private static final String RUNTIME_PATH = "/internal/llm/runtime/";
    private static final String TURN_PATH = "/internal/llm/turn/";

    /** Where the conversation loop pulls turn assignments, with its own credential. */
    static final String ASSIGNMENTS_PATH = "/internal/llm/turn-assignments";

    private static final BearerTokenAuthenticationConverter CONVERTER = converter();
    private static final List<IpAddressMatcher> PRIVATE_RANGES = List.of(
            new IpAddressMatcher("10.0.0.0/8"),
            new IpAddressMatcher("172.16.0.0/12"),
            new IpAddressMatcher("192.168.0.0/16"),
            new IpAddressMatcher("127.0.0.0/8"),
            new IpAddressMatcher("169.254.0.0/16"),
            new IpAddressMatcher("::1"),
            new IpAddressMatcher("fe80::/10"),
            new IpAddressMatcher("fc00::/7"));

    JobTokenAuthenticationFilter(
            ReviewJobRepository jobs,
            AgentRuns runs,
            MentorTurnRepository turns,
            ConversationLoopProperties loop,
            AttemptTokenVerifier verifier,
            @Nullable AgentSandboxes sandboxes,
            Map<AgentRunKind, SandboxRunKind> kinds,
            ProxyAccounting accounting) {
        super(
                new ProviderManager(
                        new JobTokenAuthenticationProvider(jobs, runs, turns, loop, verifier, sandboxes, kinds)),
                JobTokenAuthenticationFilter::convert);
        setSuccessHandler((request, response, authentication) -> {
            var identity = ((JobTokenAuthentication) authentication).getPrincipal();
            var caller = identity.job() == null ? identity.turn() : identity.job();
            if (caller == null) return;
            MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(caller.workspaceId()));
            MDC.put(StructuredLogKeys.RUN_ID, caller.runId().toString());
            MDC.put(StructuredLogKeys.RUN_EPOCH, Integer.toString(caller.epoch()));
            MDC.remove(StructuredLogKeys.REVIEW_ID);
            MDC.remove(StructuredLogKeys.SUBJECT_KEY);
            MDC.remove(StructuredLogKeys.JOB_ATTEMPT);
            MDC.remove(StructuredLogKeys.JOB_ID);
            if (caller.caller() == ProxyAttempt.Caller.REVIEW) {
                MDC.put(StructuredLogKeys.JOB_ID, caller.id().toString());
                MDC.put(StructuredLogKeys.JOB_ATTEMPT, Integer.toString(ReviewRunKind.attempt(caller.epoch())));
            }
            runs.find(new AgentRunRef(caller.runId(), caller.workspaceId())).ifPresent(run -> {
                String subject = run.getConcurrencyKey();
                MDC.put(StructuredLogKeys.SUBJECT_KEY, subject);
                if (subject.startsWith("review:")) MDC.put(StructuredLogKeys.REVIEW_ID, subject.substring(7));
            });
        });
        setFailureHandler((request, response, failure) -> {
            if (failure instanceof JobTokenAuthenticationProvider.AttemptLeaseConflict) {
                accounting.gatewayRefused(SandboxGatewayRefusal.ATTEMPT_ENDED);
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.getWriter().write(ATTEMPT_ENDED);
            } else {
                accounting.gatewayRefused(SandboxGatewayRefusal.UNAUTHENTICATED);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
        });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var previous = MDC.getCopyOfContextMap();
        try {
            super.doFilterInternal(request, response, chain);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    private static BearerTokenAuthenticationConverter converter() {
        var converter = new BearerTokenAuthenticationConverter();
        converter.setAuthenticationDetailsSource(incoming -> {
            String path = incoming.getRequestURI();
            return new RequestIdentity(
                    segment(path, RUNTIME_PATH),
                    segment(path, TURN_PATH),
                    "POST".equals(incoming.getMethod()) && path.equals(ASSIGNMENTS_PATH),
                    incoming.getHeader(SandboxRunArgumentResolver.RUN_HEADER),
                    incoming.getHeader(SandboxRunArgumentResolver.EPOCH_HEADER));
        });
        return converter;
    }

    private static @Nullable Authentication convert(HttpServletRequest request) {
        if (!isPrivateIp(request.getRemoteAddr())) throw new BadCredentialsException("Private gateway required");
        var authentication = CONVERTER.convert(request);
        if (authentication == null) throw new BadCredentialsException("Bearer credential required");
        return authentication;
    }

    /** The path segment after {@code prefix}: the runtime or turn id the request names; {@code null} elsewhere. */
    private static @Nullable String segment(String path, String prefix) {
        if (!path.startsWith(prefix)) return null;
        int end = path.indexOf('/', prefix.length());
        return path.substring(prefix.length(), end < 0 ? path.length() : end);
    }

    /**
     * What the request asks for, which decides what a credential may reach.
     *
     * @param turnId the turn a turn endpoint names
     * @param assignments whether this is the conversation loop's assignment pull
     * @param run the run that a sandbox's call names, with {@code epoch} the claim it holds
     */
    record RequestIdentity(
            @Nullable String runtimeJobId,
            @Nullable String turnId,
            boolean assignments,
            @Nullable String run,
            @Nullable String epoch) {}

    static boolean isPrivateIp(@Nullable String ip) {
        if (ip == null || (!InetAddressUtils.isIPv4(ip) && !InetAddressUtils.isIPv6(ip))) return false;
        return PRIVATE_RANGES.stream().anyMatch(range -> range.matches(ip));
    }
}
