package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.SecurityConfig;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@TestConfiguration
@Import(SecurityConfig.class)
@Profile("test")
public class TestSecurityConfig {

    public static final String IMPERSONATION_TOKEN = "mock-jwt-token-for-impersonation";

    public static final String NUMERIC_SUBJECT_TOKEN = "mock-jwt-token-for-numeric-user";
    private static final String IMPERSONATION_JTI = "11111111-1111-1111-1111-111111111111";
    private static final String NUMERIC_JTI = "22222222-2222-2222-2222-222222222222";

    /** Mock sessions are recently authenticated. StepUpGateIntegrationTest covers stale authentication
     * using the real issuer and decoder. Named fixtures must be linked during setup; decoding is read-only. */
    @Bean
    @Primary
    public JwtDecoder mockJwtDecoder(
            UserRepository users, IdentityProviderRepository providers, IdentityLinkRepository identities) {
        return token -> {
            // RFC 8693 act marks impersonation; logout also requires a valid jti.
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

            // mock-jwt-sub grants application roles; mock-jwt-user-sub tests membership without them.
            boolean regularAccount = token.startsWith("mock-jwt-user-sub-");
            if (regularAccount || token.startsWith("mock-jwt-sub-")) {
                String prefix = regularAccount ? "mock-jwt-user-sub-" : "mock-jwt-sub-";
                String sub = token.substring(prefix.length());
                return Jwt.withTokenValue(token)
                        .header("alg", "HS256")
                        .header("typ", "JWT")
                        .claim("sub", sub)
                        .claim("preferred_username", "account-" + sub)
                        .claim("iss", "https://test-issuer")
                        .claim("aud", "test-audience")
                        .claim("roles", regularAccount ? List.of() : Arrays.asList("mentor_access", "app_admin"))
                        .claim("auth_time", Instant.now().getEpochSecond())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            }

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

                username = "testuser";
                userId = "test-user-id";
                roles = new String[] {};
            }

            Map<String, Object> claims = new HashMap<>();
            claims.put(
                    "sub",
                    providers
                            .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                            .flatMap(provider ->
                                    users.findByLoginAndProviderId(username, Objects.requireNonNull(provider.getId())))
                            .flatMap(actor -> identities.findActiveByProviderSubject(
                                    Objects.requireNonNull(actor.getProvider().getId()),
                                    actor.getNativeId().toString(),
                                    null))
                            .map(link -> Objects.requireNonNull(
                                            link.getAccount().getId())
                                    .toString())
                            .orElse(userId));
            claims.put("preferred_username", username);
            claims.put("iss", "https://test-issuer");
            claims.put("aud", "test-audience");

            claims.put("auth_time", Instant.now().getEpochSecond());

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
