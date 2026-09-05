package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.SecurityConfig;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test security configuration that imports the main SecurityConfig and only overrides
 * the JWT decoder with a mock implementation. This ensures we use the same security
 * configuration as production but with a test-friendly JWT decoder.
 */
@TestConfiguration
@Import(SecurityConfig.class)
@Profile("test")
public class TestSecurityConfig {

    /** Stable token strings + claims used by the impersonation-guard integration test. */
    public static final String IMPERSONATION_TOKEN = "mock-jwt-token-for-impersonation";

    public static final String NUMERIC_SUBJECT_TOKEN = "mock-jwt-token-for-numeric-user";
    private static final String IMPERSONATION_JTI = "11111111-1111-1111-1111-111111111111";
    private static final String NUMERIC_JTI = "22222222-2222-2222-2222-222222222222";

    /**
     * Mock JWT decoder that creates a valid JWT for testing.
     * This decoder will be used by the main SecurityConfig's OAuth2 resource server configuration.
     * The JWT carries the same flat `roles` claim the Hephaestus issuer emits (ADR 0017).
     *
     * It dynamically determines the user based on the token value pattern:
     * - "mock-jwt-token-for-mentor-user" -> mentor user
     * - "mock-jwt-token-for-admin-user" -> admin user
     * - "mock-jwt-token-for-test-user" -> test user
     * - any other token -> defaults to testuser
     *
     * <p>Every mock session carries {@code auth_time = now}: a mock token stands for a browser that just
     * completed the OAuth dance, so it satisfies the recent-sign-in gate the same way a real one would.
     * The gate's negatives are proven against the real issuer and decoder in {@code StepUpGateIntegrationTest},
     * and {@code RecentSignInByDefaultArchTest} is what fails when a new admin mutation forgets the gate —
     * neither depends on this decoder.
     */
    @Bean
    @Primary
    public JwtDecoder mockJwtDecoder() {
        return token -> {
            // Impersonation token: numeric sub + RFC 8693 `act` claim so ImpersonationGuard treats
            // the session as read-only. A valid jti keeps the controller path (logout) clean.
            if (IMPERSONATION_TOKEN.equals(token)) {
                return Jwt.withTokenValue(token)
                        .header("alg", "ES256")
                        .header("typ", "JWT")
                        .claim("sub", "1")
                        .claim("preferred_username", "impersonated")
                        .claim("iss", "https://test-issuer")
                        .claim("aud", "test-audience")
                        .claim("jti", IMPERSONATION_JTI)
                        .claim("roles", Arrays.asList("app_admin"))
                        .claim("act", Map.of("sub", "2"))
                        .claim("auth_time", Instant.now().getEpochSecond())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            }
            // Plain (non-act) token with a numeric sub + valid jti — a normal write must be allowed.
            if (NUMERIC_SUBJECT_TOKEN.equals(token)) {
                return Jwt.withTokenValue(token)
                        .header("alg", "ES256")
                        .header("typ", "JWT")
                        .claim("sub", "1")
                        .claim("preferred_username", "numericuser")
                        .claim("iss", "https://test-issuer")
                        .claim("aud", "test-audience")
                        .claim("jti", NUMERIC_JTI)
                        .claim("auth_time", Instant.now().getEpochSecond())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            }

            // Dynamic numeric-subject token: "mock-jwt-sub-<accountId>" decodes to that exact `sub`,
            // so a test can authenticate AS a specific (DB-assigned) Account id — required since the
            // native-auth migration keys currentAccountId() on a numeric JWT sub (ADR 0017). Carries the
            // common roles so it works for both authenticated and mentor-gated endpoints.
            if (token.startsWith("mock-jwt-sub-")) {
                String sub = token.substring("mock-jwt-sub-".length());
                return Jwt.withTokenValue(token)
                        .header("alg", "HS256")
                        .header("typ", "JWT")
                        .claim("sub", sub)
                        .claim("preferred_username", "account-" + sub)
                        .claim("iss", "https://test-issuer")
                        .claim("aud", "test-audience")
                        .claim("roles", Arrays.asList("mentor_access", "app_admin"))
                        .claim("auth_time", Instant.now().getEpochSecond())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            }

            // Determine user based on token pattern
            String username;
            String userId;
            String[] roles;

            if ("mock-jwt-token-for-mentor-user".equals(token)) {
                username = "mentor";
                userId = "mentor-user-id";
                roles = new String[] {"mentor_access"};
            } else if ("mock-jwt-token-for-admin-user".equals(token)) {
                username = "admin";
                userId = "admin-user-id";
                roles = new String[] {"app_admin"};
            } else if ("mock-jwt-token-for-test-user".equals(token)) {
                username = "testuser";
                userId = "test-user-id";
                roles = new String[] {};
            } else {
                // Default fallback
                username = "testuser";
                userId = "test-user-id";
                roles = new String[] {};
            }

            // Create a mock JWT that matches the structure expected by the main SecurityConfig
            Map<String, Object> claims = new HashMap<>();
            claims.put("sub", userId);
            claims.put("preferred_username", username);
            claims.put("iss", "https://test-issuer");
            claims.put("aud", "test-audience");

            claims.put("auth_time", Instant.now().getEpochSecond());

            // Flat `roles` claim — same shape the Hephaestus issuer emits (ADR 0017).
            if (roles.length > 0) {
                claims.put("roles", Arrays.asList(roles));
            }

            return Jwt.withTokenValue(token)
                    .header("alg", "HS256")
                    .header("typ", "JWT")
                    .claims(claimsMap -> claimsMap.putAll(claims))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        };
    }
}
