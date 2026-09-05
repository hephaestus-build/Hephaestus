package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.auth.jwt.CookieBearerTokenResolver;
import de.tum.cit.aet.hephaestus.core.auth.jwt.RevocationAwareJwtDecoder;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.core.auth.stepup.RecentSignInPolicy;
import de.tum.cit.aet.hephaestus.core.auth.stepup.StepUpRequiredException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Who is allowed to attach a new identity, on the OAuth chain where no SecurityContext exists. */
class IdentityLinkAuthenticationTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-06-02T10:00:00Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private CookieBearerTokenResolver bearerTokenResolver;
    private RevocationAwareJwtDecoder jwtDecoder;
    private IdentityLinkAuthentication identityLinkAuthentication;

    @BeforeEach
    void setUp() {
        bearerTokenResolver = mock(CookieBearerTokenResolver.class);
        jwtDecoder = mock(RevocationAwareJwtDecoder.class);
        // Stands in for SecurityConfig's authority converter; that the production one mints the same
        // factor from a real token is proven end to end in StepUpGateIntegrationTest.
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> jwt.getClaim("auth_time") instanceof Number seconds
                ? List.of(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                        .issuedAt(Instant.ofEpochSecond(seconds.longValue()))
                        .build())
                : List.of());
        RecentSignInPolicy policy = new RecentSignInPolicy(
                AuthPropertiesFixture.withStepUpMaxAge(MAX_AGE),
                new AuthEventLogger(mock(AuthEventWriter.class)),
                new AuthMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        identityLinkAuthentication =
                new IdentityLinkAuthentication(bearerTokenResolver, jwtDecoder, converter::convert, policy);
    }

    private void presenting(@Nullable Jwt jwt) {
        when(bearerTokenResolver.resolve(any())).thenReturn(jwt == null ? null : "token");
        if (jwt != null) {
            when(jwtDecoder.decode("token")).thenReturn(jwt);
        }
    }

    private static Jwt.Builder session(String sub) {
        return Jwt.withTokenValue("token").header("alg", "ES256").subject(sub);
    }

    @Test
    void noSessionMeansNoAccountToLinkInto() {
        presenting(null);

        assertThat(identityLinkAuthentication.resolveAuthenticatedAccountId(mock(HttpServletRequest.class)))
                .isNull();
    }

    @Test
    void anInvalidOrRevokedTokenMeansNoAccountToLinkInto() {
        when(bearerTokenResolver.resolve(any())).thenReturn("token");
        when(jwtDecoder.decode("token")).thenThrow(new JwtException("revoked"));

        assertThat(identityLinkAuthentication.resolveAuthenticatedAccountId(mock(HttpServletRequest.class)))
                .isNull();
    }

    /** An operator acting as someone else must not be able to install a second way into their account. */
    @Test
    void anImpersonatedSessionMayNotLink() {
        presenting(session("7")
                .claim("act", Map.of("sub", "2"))
                .claim("auth_time", NOW.getEpochSecond())
                .build());

        assertThat(identityLinkAuthentication.resolveAuthenticatedAccountId(mock(HttpServletRequest.class)))
                .isNull();
    }

    @Test
    void aStaleSignInIsAskedToConfirmAccess() {
        presenting(session("7")
                .claim("auth_time", NOW.minus(MAX_AGE).getEpochSecond())
                .build());

        assertThatThrownBy(
                        () -> identityLinkAuthentication.resolveAuthenticatedAccountId(mock(HttpServletRequest.class)))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void aRecentSignInResolvesToItsOwnAccount() {
        presenting(session("7")
                .claim("auth_time", NOW.minusSeconds(30).getEpochSecond())
                .build());

        assertThat(identityLinkAuthentication.resolveAuthenticatedAccountId(mock(HttpServletRequest.class)))
                .isEqualTo(7L);
    }

    @Test
    void aTokenWithNoRecordedSignInIsAskedToConfirmAccess() {
        presenting(session("7").claim("scope", "x").build());

        assertThatThrownBy(
                        () -> identityLinkAuthentication.resolveAuthenticatedAccountId(mock(HttpServletRequest.class)))
                .isInstanceOf(StepUpRequiredException.class);
    }
}
