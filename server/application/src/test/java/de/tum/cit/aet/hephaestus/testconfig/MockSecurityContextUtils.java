package de.tum.cit.aet.hephaestus.testconfig;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Base utility class for creating mock security contexts with JWT authentication.
 * Eliminates code duplication across different user type security context factories.
 */
public class MockSecurityContextUtils {

    /**
     * Creates a security context with JWT authentication for the specified user.
     *
     * @param userId the user ID for the JWT claims
     * @param authorities the authorities/roles for the user
     * @param tokenValue the JWT token value (used to identify user type in TestSecurityConfig)
     * @return configured SecurityContext
     */
    public static SecurityContext createSecurityContext(String userId, String[] authorities, String tokenValue) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();

        // Create mock JWT claims
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", userId);
        claims.put("iss", "https://test-issuer");
        claims.put("aud", "test-audience");

        // Flat `roles` claim (ADR 0017)
        if (authorities.length > 0) {
            claims.put("roles", Arrays.asList(authorities));
        }

        // Create mock JWT with specified token value
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "HS256")
                .header("typ", "JWT")
                .claims(claimsMap -> claimsMap.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        // Create authorities from the annotation
        var springAuthorities =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).collect(Collectors.toList());

        // Create JWT authentication token
        Authentication authentication = new JwtAuthenticationToken(jwt, springAuthorities);
        context.setAuthentication(authentication);

        return context;
    }
}
