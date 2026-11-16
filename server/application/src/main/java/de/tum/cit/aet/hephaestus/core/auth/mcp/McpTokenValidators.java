package de.tum.cit.aet.hephaestus.core.auth.mcp;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * What an access token must be to reach a workspace MCP endpoint, beyond signature, issuer and revocation:
 * one audience that is an MCP endpoint of this issuer, a client session, and no roles (ADR 0110 decision 11).
 * The request's own endpoint and the grant are checked by {@link McpGrantGuard}.
 */
public final class McpTokenValidators {

    private McpTokenValidators() {}

    public static OAuth2TokenValidator<Jwt> mcpAudience(McpResources resources) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            if (audience == null
                    || audience.size() != 1
                    || resources.slugOf(audience.getFirst()).isEmpty()) {
                return invalid("the token is not for a workspace MCP endpoint");
            }
            if (jwt.hasClaim("roles")) {
                return invalid("an MCP token carries no roles");
            }
            if (!jwt.hasClaim("sid")) {
                return invalid("an MCP token belongs to a client session");
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
