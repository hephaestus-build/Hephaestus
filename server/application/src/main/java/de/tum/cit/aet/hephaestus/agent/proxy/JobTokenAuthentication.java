package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import java.io.Serial;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A validated proxy-scoped bearer token: an {@code AgentJob}'s job token or a Heph turn attempt's turn token. The
 * resolved {@link ProxyRouting} is the principal; a turn token's claims are the details, which the turn endpoints
 * read the turn's developer and conversation from.
 */
class JobTokenAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    // Proxy authentication is stateless request-local routing, never an HTTP session or serialized token.
    @SuppressWarnings("serial")
    private final ProxyRouting routing;

    JobTokenAuthentication(ProxyRouting routing, @Nullable TurnJwt turn) {
        super(List.of());
        this.routing = routing;
        setDetails(turn);
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
