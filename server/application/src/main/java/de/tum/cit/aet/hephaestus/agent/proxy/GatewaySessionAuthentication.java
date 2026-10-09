package de.tum.cit.aet.hephaestus.agent.proxy;

import java.io.Serial;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** The Heph host's gateway session token; {@link JobTokenAuthenticationFilter} says what it reaches. */
final class GatewaySessionAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    GatewaySessionAuthentication(UUID sessionId) {
        super(List.of());
        this.sessionId = sessionId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "[REDACTED]";
    }

    @Override
    public String getPrincipal() {
        return "gateway-session:" + sessionId;
    }
}
