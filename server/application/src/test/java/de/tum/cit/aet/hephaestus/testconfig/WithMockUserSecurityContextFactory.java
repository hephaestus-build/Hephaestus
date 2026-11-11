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
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * Factory to create a mock security context with JWT authentication for tests.
 */
public class WithMockUserSecurityContextFactory implements WithSecurityContextFactory<WithMockUser> {

    @Override
    public SecurityContext createSecurityContext(WithMockUser annotation) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();

        // Create mock JWT claims
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", annotation.userId());
        claims.put("iss", "https://test-issuer");
        claims.put("aud", "test-audience");

        // Flat `roles` claim (ADR 0017)
        if (annotation.authorities().length > 0) {
            claims.put("roles", Arrays.asList(annotation.authorities()));
        }

        // Create mock JWT
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "HS256")
                .header("typ", "JWT")
                .claims(claimsMap -> claimsMap.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        // Create authorities from the annotation
        var authorities = Arrays.stream(annotation.authorities())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        // Create JWT authentication token
        Authentication authentication = new JwtAuthenticationToken(jwt, authorities);
        context.setAuthentication(authentication);

        return context;
    }
}
