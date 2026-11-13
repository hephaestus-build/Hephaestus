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
    static final List<String> LOG_CONTEXT_KEYS = List.of(
            StructuredLogKeys.SANDBOX_NAME,
            StructuredLogKeys.SUBJECT_KEY,
            StructuredLogKeys.WORKSPACE_ID,
            StructuredLogKeys.RUN_ID,
            StructuredLogKeys.RUN_EPOCH,
            StructuredLogKeys.REVIEW_ID,
            StructuredLogKeys.JOB_ID,
            StructuredLogKeys.JOB_ATTEMPT);

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

    /** Async response completion crosses the same authenticated gateway boundary as its initial dispatch. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
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
        // The resolver adds the checked run and epoch. No enclosing run belongs to this request; all of its
        // correlation is restored when the request ends, including when a handler fails.
        for (String key : LOG_CONTEXT_KEYS) MDC.remove(key);
        MDC.put(StructuredLogKeys.SANDBOX_NAME, live.claimName());
        MDC.put(StructuredLogKeys.SUBJECT_KEY, live.subjectKey());
        MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(live.workspaceId()));
        String reviewId = live.reviewId();
        if (reviewId != null) MDC.put(StructuredLogKeys.REVIEW_ID, reviewId);
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
