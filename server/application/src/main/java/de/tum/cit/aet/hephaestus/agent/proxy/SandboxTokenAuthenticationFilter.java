package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
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
        var context = contexts.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(sandbox.get(), null, List.of()));
        contexts.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            contexts.clearContext();
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
