package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import java.io.Serial;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A Heph turn credential on one of its turn endpoints. Its claims are the details, which the endpoints read the turn's
 * developer and conversation from; no model routing is resolved, because these endpoints call no model.
 */
final class TurnEndpointAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String principal;

    TurnEndpointAuthentication(TurnJwt turn) {
        super(List.of());
        this.principal = "mentor-turn:" + turn.turnId();
        setDetails(turn);
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "[REDACTED]";
    }

    /** The same name the turn's model calls are limited under. */
    @Override
    public String getPrincipal() {
        return principal;
    }
}
