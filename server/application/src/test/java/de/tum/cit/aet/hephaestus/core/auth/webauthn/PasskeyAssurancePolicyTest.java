package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class PasskeyAssurancePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final Account account = new Account("Ada");

    private PasskeyAssurancePolicy policy(boolean instance, boolean workspace) {
        return policy(instance, workspace, true);
    }

    private PasskeyAssurancePolicy policy(boolean instance, boolean workspace, boolean enforced) {
        account.setId(42L);
        when(accounts.findById(42L)).thenReturn(Optional.of(account));
        return new PasskeyAssurancePolicy(
                new PasskeyProperties(instance, workspace, "example.com", List.of(), Duration.ofHours(6)),
                accounts,
                enforced ? AuthPropertiesFixture.defaults() : AuthPropertiesFixture.withoutAssurance(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PasskeySecurityEvents.class));
    }

    private Authentication authentication(Instant time, boolean client) {
        var jwt = Jwt.withTokenValue("test")
                .header("alg", "ES256")
                .subject("42")
                .claim("passkey_time", time.getEpochSecond());
        if (client) {
            jwt.claim("sid", "installed-client");
        }
        return new JwtAuthenticationToken(
                jwt.build(),
                List.of(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
                        .issuedAt(time)
                        .build()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        UserViewContextHolder.clear();
    }

    @Test
    void shouldRequireFreshPasskeyWhenDeploymentPolicyIsOptional() {
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("oauth")
                        .header("alg", "ES256")
                        .subject("42")
                        .claim("auth_time", NOW.getEpochSecond())
                        .build()));
        assertThatThrownBy(() -> policy(false, false).requireFresh()).isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldRejectStaleProofWhenFreshPasskeyIsRequired() {
        SecurityContextHolder.getContext().setAuthentication(authentication(NOW.minus(Duration.ofMinutes(6)), false));
        assertThatThrownBy(() -> policy(false, false).requireFresh()).isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldRejectInstalledClientWhenFreshPasskeyIsRequired() {
        SecurityContextHolder.getContext().setAuthentication(authentication(NOW, true));
        assertThatThrownBy(() -> policy(false, false).requireFresh()).isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldRejectUserViewWhenFreshPasskeyIsRequired() {
        SecurityContextHolder.getContext().setAuthentication(authentication(NOW, false));
        UserViewContextHolder.set(new UserViewContextHolder.View(7L, 99L));
        assertThatThrownBy(() -> policy(true, false).requireFresh())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void shouldAcceptRecentPasskeyWhenFreshPasskeyIsRequired() {
        SecurityContextHolder.getContext().setAuthentication(authentication(NOW.minusSeconds(30), false));
        policy(false, false).requireFresh();
    }

    @Test
    void shouldRejectOAuthOnlyAdminWhenInstanceRequiresPasskeys() {
        var jwt = Jwt.withTokenValue("oauth")
                .header("alg", "ES256")
                .subject("42")
                .claim("auth_time", NOW.getEpochSecond())
                .build();
        assertThatThrownBy(() -> policy(true, false).requireInstanceAdmin(new JwtAuthenticationToken(jwt), false))
                .isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldRejectAnUnresolvableSubjectEvenWhenProtectionIsOptional() {
        var jwt = Jwt.withTokenValue("oauth")
                .header("alg", "ES256")
                .subject("not-an-account")
                .build();
        assertThatThrownBy(() -> policy(false, false).requireInstanceAdmin(new JwtAuthenticationToken(jwt), false))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void shouldPermitOptionalAdminWhenNoPersonalProtectionExists() {
        var jwt =
                Jwt.withTokenValue("oauth").header("alg", "ES256").subject("42").build();
        policy(false, false).requireInstanceAdmin(new JwtAuthenticationToken(jwt), true);
    }

    @Test
    void shouldAcceptFreshPasskeyWhenInstanceRequiresIt() {
        policy(true, false).requireInstanceAdmin(authentication(NOW, false), true);
    }

    @Test
    void shouldRejectStalePasskeyForSensitiveAdminAction() {
        assertThatThrownBy(() -> policy(true, false)
                        .requireInstanceAdmin(authentication(NOW.minus(Duration.ofMinutes(6)), false), true))
                .isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldKeepGeneralAdminAccessWhenPasskeyIsOlderThanSensitiveWindow() {
        policy(true, false).requireInstanceAdmin(authentication(NOW.minus(Duration.ofMinutes(6)), false), false);
    }

    @Test
    void shouldAskAdminToVerifyAgainWhenPasskeyIsOlderThanAdminWindow() {
        var policy = policy(true, false);
        policy.requireInstanceAdmin(authentication(NOW.minus(Duration.ofHours(5)), false), false);
        assertThatThrownBy(
                        () -> policy.requireInstanceAdmin(authentication(NOW.minus(Duration.ofHours(7)), false), false))
                .isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldAskWorkspaceAdminToVerifyAgainWhenPasskeyIsOlderThanAdminWindow() {
        SecurityContextHolder.getContext().setAuthentication(authentication(NOW.minus(Duration.ofHours(7)), false));
        assertThatThrownBy(() -> policy(false, true).requireWorkspaceAdmin(false, false, false))
                .isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldRejectAdminAccessDuringRecoveryEvenWithPasskeyClaim() {
        account.setPasskeyRecoveryRequired(true);
        assertThatThrownBy(() -> policy(true, false).requireInstanceAdmin(authentication(NOW, false), false))
                .isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldRejectInstalledClientProof() {
        assertThat(policy(true, false).verified(authentication(NOW, true), Duration.ofMinutes(5)))
                .isFalse();
    }

    @Test
    void shouldRejectFutureProofOutsideClockTolerance() {
        assertThat(policy(true, false).verified(authentication(NOW.plusSeconds(60), false), Duration.ofMinutes(5)))
                .isFalse();
    }

    @Test
    void shouldEnforceWorkspaceRequirementWithoutInstanceRequirement() {
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("oauth")
                        .header("alg", "ES256")
                        .subject("42")
                        .build()));
        assertThatThrownBy(() -> policy(false, false).requireWorkspaceAdmin(true, false, false))
                .isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldEnforcePersonalProtectionWhenDeploymentIsOptional() {
        account.setPasskeyProtectionEnabled(true);
        assertThatThrownBy(() -> policy(false, false)
                        .requireInstanceAdmin(
                                new JwtAuthenticationToken(Jwt.withTokenValue("oauth")
                                        .header("alg", "ES256")
                                        .subject("42")
                                        .build()),
                                false))
                .isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldSuspendConfiguredAndPersonalRequirementsOnlyWhenOperatorDisablesAssurance() {
        account.setPasskeyProtectionEnabled(true);
        account.setPasskeyRecoveryRequired(true);
        Authentication oauth = new JwtAuthenticationToken(
                Jwt.withTokenValue("oauth").header("alg", "ES256").subject("42").build());
        SecurityContextHolder.getContext().setAuthentication(oauth);
        var disabled = policy(true, true, false);
        disabled.requireInstanceAdmin(oauth, true);
        disabled.requireWorkspaceAdmin(true, true, true);
        disabled.requireFresh();
        disabled.requireEnrollment(true, false);
        disabled.requireEnrollment(false, false);
        assertThat(disabled.isFresh(oauth)).isFalse();
        assertThat(disabled.assuranceEnforced()).isFalse();
        assertThatThrownBy(() -> policy(true, true).requireInstanceAdmin(oauth, true))
                .isInstanceOf(PasskeyRequiredException.class);
    }

    @Test
    void shouldKeepUserViewWriteProhibitionWhenAssuranceIsDisabled() {
        SecurityContextHolder.getContext().setAuthentication(authentication(NOW, false));
        UserViewContextHolder.set(new UserViewContextHolder.View(7L, 99L));
        assertThatThrownBy(() -> policy(true, true, false).requireFresh())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void shouldRejectMissingAccountAndInstalledClientWhenAssuranceIsDisabled() {
        var disabled = policy(true, true, false);
        assertThatThrownBy(disabled::requireFresh).isInstanceOf(PasskeyRequiredException.class);
        assertThatThrownBy(() -> disabled.requireInstanceAdmin(null, true))
                .isInstanceOf(PasskeyRequiredException.class);
        SecurityContextHolder.getContext().setAuthentication(authentication(NOW, true));
        assertThatThrownBy(disabled::requireFresh).isInstanceOf(PasskeyRequiredException.class);
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("oauth")
                        .header("alg", "ES256")
                        .subject("77")
                        .build()));
        assertThatThrownBy(disabled::requireFresh).isInstanceOf(PasskeyRequiredException.class);
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("oauth")
                        .header("alg", "ES256")
                        .subject("invalid")
                        .build()));
        assertThatThrownBy(disabled::requireFresh)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
