package de.tum.cit.aet.hephaestus.config;

import java.time.Instant;
import java.util.Collections;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Lightweight security overrides that allow the {@code specs} profile to boot without
 * a configured JWT decoder. OpenAPI generation does not require real token validation,
 * so we short-circuit JWT decoding with a deterministic stub that still satisfies the
 * production {@link de.tum.cit.aet.hephaestus.SecurityConfig} contract.
 */
@Configuration
@Profile("specs")
public class SpecsSecurityConfig {

    @Bean
    @Primary
    JwtDecoder specsJwtDecoder() {
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .header("typ", "JWT")
                .claims(claims -> {
                    claims.put("sub", "specs-profile-user");
                    claims.put("iss", "https://localhost/specs-profile");
                    claims.put("aud", "hephaestus-api");
                    // Flat `roles` claim so the authority converter works (ADR 0017).
                    claims.put("roles", Collections.emptyList());
                })
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
