package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.AttemptJwt;
import java.io.Serial;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * A verified sandbox credential. Token scopes become {@code SCOPE_} authorities, as Spring's {@code hasScope}
 * requires. A turn's token is also the details, which the turn endpoints read the attempt from.
 */
class JobTokenAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    // why: AuthenticationFilter stores this identity in RequestAttributeSecurityContextRepository only.
    @SuppressWarnings("serial")
    private final ProxyIdentity identity;

    JobTokenAuthentication(ProxyIdentity identity, Set<String> scopes, @Nullable AttemptJwt turn) {
        super(scopes.stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList());
        this.identity = identity;
        setDetails(turn);
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "[REDACTED]";
    }

    @Override
    public ProxyIdentity getPrincipal() {
        return identity;
    }
}
