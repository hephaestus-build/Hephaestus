package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit coverage for the two authz-load-bearing helpers:
 * {@link SecurityUtils#getCurrentAccountId()} (resolves the {@code sub} → numeric account id that
 * {@code CurrentAccountUsers} keys identity resolution on) and {@link SecurityUtils#isSuperAdmin()}
 * (which {@code WorkspaceAccessService} elevates to workspace-ADMIN on). Both must fail closed on every
 * malformed / missing-principal branch.
 */
class SecurityUtilsTest extends BaseUnitTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithJwt(@Nullable String subject, Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (subject != null) {
            builder.subject(subject);
        } else {
            // A Jwt requires a non-null token value but the `sub` claim may legitimately be absent.
            builder.claim("preferred_username", "no-sub");
        }
        extraClaims.forEach(builder::claim);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
    }

    // --- getCurrentAccountId ---

    @Test
    @DisplayName("getCurrentAccountId parses a numeric sub")
    void accountId_parsesNumericSub() {
        authenticateWithJwt("42", Map.of());
        assertThat(SecurityUtils.getCurrentAccountId()).contains(42L);
    }

    @Test
    @DisplayName("getCurrentAccountId is empty when no Authentication is present")
    void accountId_emptyWhenUnauthenticated() {
        assertThat(SecurityUtils.getCurrentAccountId()).isEmpty();
    }

    @Test
    @DisplayName("getCurrentAccountId is empty for a non-Jwt principal")
    void accountId_emptyForNonJwtPrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("alice", "pw", AuthorityUtils.NO_AUTHORITIES));
        assertThat(SecurityUtils.getCurrentAccountId()).isEmpty();
    }

    @Test
    @DisplayName("getCurrentAccountId is empty for a null/blank sub")
    void accountId_emptyForNullOrBlankSub() {
        authenticateWithJwt(null, Map.of());
        assertThat(SecurityUtils.getCurrentAccountId()).isEmpty();

        authenticateWithJwt("   ", Map.of());
        assertThat(SecurityUtils.getCurrentAccountId()).isEmpty();
    }

    @Test
    @DisplayName("getCurrentAccountId is empty for a non-numeric sub")
    void accountId_emptyForNonNumericSub() {
        authenticateWithJwt("abc", Map.of());
        assertThat(SecurityUtils.getCurrentAccountId()).isEmpty();
    }

    // --- isSuperAdmin ---

    @Test
    @DisplayName("isSuperAdmin is true only when the roles claim contains app_admin")
    void superAdmin_trueWhenRolesContainAppAdmin() {
        authenticateWithJwt("1", Map.of("roles", List.of("notification_access", SecurityUtils.APP_ADMIN_AUTHORITY)));
        assertThat(SecurityUtils.isSuperAdmin()).isTrue();
    }

    @Test
    @DisplayName("isSuperAdmin is false when the roles list lacks app_admin")
    void superAdmin_falseWhenRolesLackAppAdmin() {
        authenticateWithJwt("1", Map.of("roles", List.of("notification_access", "run_practice_review")));
        assertThat(SecurityUtils.isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("isSuperAdmin is false when the roles claim is absent")
    void superAdmin_falseWhenRolesAbsent() {
        authenticateWithJwt("1", Map.of());
        assertThat(SecurityUtils.isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("isSuperAdmin is false when the roles claim is not a List")
    void superAdmin_falseWhenRolesWrongType() {
        authenticateWithJwt("1", Map.of("roles", SecurityUtils.APP_ADMIN_AUTHORITY));
        assertThat(SecurityUtils.isSuperAdmin()).isFalse();
    }

    @Test
    @DisplayName("isSuperAdmin is false when unauthenticated or a non-Jwt principal")
    void superAdmin_falseWhenUnauthenticatedOrNonJwt() {
        assertThat(SecurityUtils.isSuperAdmin()).isFalse();

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("alice", "pw", AuthorityUtils.NO_AUTHORITIES));
        assertThat(SecurityUtils.isSuperAdmin()).isFalse();
    }
}
