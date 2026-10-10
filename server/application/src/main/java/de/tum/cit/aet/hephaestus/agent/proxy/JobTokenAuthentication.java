package de.tum.cit.aet.hephaestus.agent.proxy;

import java.io.Serial;
import java.util.Set;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * A validated proxy-scoped bearer token — either an {@code AgentJob}'s job token or a mentor session's
 * registry-minted token. The resolved {@link ProxyRouting} is the principal. Each scope becomes a
 * {@code SCOPE_} authority, the form that {@code OAuth2AuthorizationManagers.hasScope} checks.
 */
class JobTokenAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    // Proxy authentication is stateless request-local routing, never an HTTP session or serialized token.
    @SuppressWarnings("serial")
    private final ProxyRouting routing;

    JobTokenAuthentication(ProxyRouting routing, Set<String> scopes) {
        super(scopes.stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList());
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
