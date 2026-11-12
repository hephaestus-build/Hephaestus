package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a sandbox on the agent routes: a sandbox token whose signature holds, and whose sandbox is live
 * before its shutdown ({@link AgentSandboxes#authenticate}). The principal is the {@link AgentSandboxes.LiveSandbox}. A wrong,
 * expired or revoked token gets the same {@code 401}.
 */
final class SandboxTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AttemptTokenVerifier verifier;
    private final @Nullable AgentSandboxes sandboxes;
    private final ProxyAccounting accounting;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    /** @param sandboxes null where this process owns no sandboxes, which refuses every token */
    SandboxTokenAuthenticationFilter(
            AttemptTokenVerifier verifier, @Nullable AgentSandboxes sandboxes, ProxyAccounting accounting) {
        this.verifier = verifier;
        this.sandboxes = sandboxes;
        this.accounting = accounting;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<AgentSandboxes.LiveSandbox> sandbox = authenticate(request);
        if (sandbox.isEmpty()) {
            accounting.gatewayRefused(SandboxGatewayRefusal.UNAUTHENTICATED);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var live = sandbox.get();
        var previous = MDC.getCopyOfContextMap();
        var context = contexts.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(live, null, List.of()));
        contexts.setContext(context);
        // Every record of the request names its sandbox. The run's resolver adds the run and the epoch once it has
        // checked them; this filter removes all of them, so a pooled thread carries none into the next request.
        MDC.put(StructuredLogKeys.SANDBOX_NAME, live.claimName());
        MDC.put(StructuredLogKeys.SUBJECT_KEY, live.subjectKey());
        MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(live.workspaceId()));
        MDC.remove(StructuredLogKeys.RUN_ID);
        MDC.remove(StructuredLogKeys.RUN_EPOCH);
        MDC.remove(StructuredLogKeys.JOB_ATTEMPT);
        MDC.remove(StructuredLogKeys.JOB_ID);
        if (live.subjectKey().startsWith("review:"))
            MDC.put(StructuredLogKeys.REVIEW_ID, live.subjectKey().substring(7));
        else MDC.remove(StructuredLogKeys.REVIEW_ID);
        try {
            chain.doFilter(request, response);
        } finally {
            contexts.clearContext();
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    private Optional<AgentSandboxes.LiveSandbox> authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        AgentSandboxes owner = sandboxes;
        if (owner == null
                || !JobTokenAuthenticationFilter.isPrivateIp(request.getRemoteAddr())
                || authorization == null
                || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) return Optional.empty();
        try {
            return owner.authenticate(verifier.verifySandbox(
                    authorization.substring(BEARER.length()).trim()));
        } catch (AttemptTokenInvalidException invalid) {
            return Optional.empty();
        }
    }
}
