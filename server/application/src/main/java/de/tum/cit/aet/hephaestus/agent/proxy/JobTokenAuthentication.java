package de.tum.cit.aet.hephaestus.agent.proxy;

import java.io.Serial;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A validated proxy-scoped bearer token — either an {@code AgentJob}'s job token or a mentor session's
 * registry-minted token. The resolved {@link ProxyRouting} is the principal.
 */
class JobTokenAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    // Proxy authentication is stateless request-local routing, never an HTTP session or serialized token.
    @SuppressWarnings("serial")
    private final ProxyRouting routing;

    JobTokenAuthentication(ProxyRouting routing) {
        super(List.of());
        this.routing = routing;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "[REDACTED]";
    }

    @Override
    public ProxyRouting getPrincipal() {
        return routing;
    }
}
