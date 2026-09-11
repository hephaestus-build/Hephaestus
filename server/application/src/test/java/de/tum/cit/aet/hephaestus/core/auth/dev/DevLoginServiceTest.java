package de.tum.cit.aet.hephaestus.core.auth.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipal;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins the passwordless dev sign-in contract: disabled by default (404), fail-closed under {@code prod}
 * (boot crash), idempotent resolve-or-create, promote-only admin, and a token minted through the real
 * issuer seam.
 */
class DevLoginServiceTest extends BaseUnitTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final JwtPrincipalFactory principalFactory = mock(JwtPrincipalFactory.class);
    private final HephaestusJwtIssuer jwtIssuer = mock(HephaestusJwtIssuer.class);

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration SESSION_MAX = Duration.ofHours(8);

    private final HephaestusJwtIssuer.Token token = new HephaestusJwtIssuer.Token(
            "jwt-value", UUID.randomUUID(), Instant.now().plusSeconds(900));

    private DevLoginService service(boolean enabled, String... activeProfiles) {
        AuthProperties props = mock(AuthProperties.class);
        when(props.devLoginEnabled()).thenReturn(enabled);
        when(props.sessionMaxLifetime()).thenReturn(SESSION_MAX);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new DevLoginService(
                props,
                accountRepository,
                principalFactory,
                jwtIssuer,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                environment);
    }

    private void stubIssuer() {
        when(principalFactory.forAccountId(anyLong())).thenReturn(new JwtPrincipal(7L, "dev", "dev", Set.of()));
        when(jwtIssuer.issue(any(), any(), any())).thenReturn(token);
    }

    @Test
    void disabled_returns404() {
        DevLoginService service = service(false, "dev");
        assertThatThrownBy(() -> service.devLogin("alice", null, false, null))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(jwtIssuer, never()).issue(any(), any(), any());
    }

    @Test
    void enabledUnderProd_failsClosedAtConstruction() {
        // Boot expands the webhook-server/worker-node group aliases to include `prod`; assert the guard
        // fires on that expanded active-profile set (acceptsProfiles catches it; a string-split wouldn't).
        assertThatThrownBy(() -> service(true, "prod", "webhook"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
        assertThatThrownBy(() -> service(true, "prod")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledNonProd_constructsWithoutThrowing() {
        assertThat(service(true, "dev", "e2e")).isNotNull();
    }

    @Test
    void newUser_createsActiveAccount_andMintsToken() {
        DevLoginService service = service(true, "dev");
        when(accountRepository.findByPrimaryEmail("alice@dev.invalid")).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account account = inv.getArgument(0);
            account.setId(7L);
            return account;
        });
        stubIssuer();

        HephaestusJwtIssuer.Token result = service.devLogin("Alice", "Alice Dev", false, null);

        assertThat(result).isEqualTo(token);
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getPrimaryEmail()).isEqualTo("alice@dev.invalid");
        assertThat(saved.getValue().getAppRole()).isEqualTo(Account.AppRole.USER);
        // The mint must stamp the same absolute session ceiling the OAuth path does (now + sessionMaxLifetime)
        // and the same auth_time, so a dev session is neither unbounded nor permanently step-up-stale.
        ArgumentCaptor<TokenConstraints> constraints = ArgumentCaptor.forClass(TokenConstraints.class);
        verify(jwtIssuer).issue(any(), constraints.capture(), any());
        assertThat(constraints.getValue()).isEqualTo(TokenConstraints.session(FIXED_NOW.plus(SESSION_MAX), FIXED_NOW));
    }

    @Test
    void admin_createsAppAdminAccount() {
        DevLoginService service = service(true, "dev");
        when(accountRepository.findByPrimaryEmail("root@dev.invalid")).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account account = inv.getArgument(0);
            account.setId(7L);
            return account;
        });
        stubIssuer();

        service.devLogin("root", null, true, null);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getAppRole()).isEqualTo(Account.AppRole.APP_ADMIN);
    }

    @Test
    void existingAccount_isReused_notRecreated() {
        DevLoginService service = service(true, "dev");
        Account existing = new Account("alice");
        existing.setId(7L);
        existing.setPrimaryEmail("alice@dev.invalid");
        existing.setAppRole(Account.AppRole.USER);
        when(accountRepository.findByPrimaryEmail("alice@dev.invalid")).thenReturn(Optional.of(existing));
        stubIssuer();

        service.devLogin("alice", null, false, null);

        // No save: the account already exists and no promotion was requested.
        verify(accountRepository, never()).save(any());
        verify(jwtIssuer).issue(any(), any(), any());
    }

    @Test
    void admin_promotesExistingUser_promoteOnly() {
        DevLoginService service = service(true, "dev");
        Account existing = new Account("alice");
        existing.setId(7L);
        existing.setPrimaryEmail("alice@dev.invalid");
        existing.setAppRole(Account.AppRole.USER);
        when(accountRepository.findByPrimaryEmail("alice@dev.invalid")).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account account = inv.getArgument(0);
            account.setId(7L);
            return account;
        });
        stubIssuer();

        service.devLogin("alice", null, true, null);

        assertThat(existing.getAppRole()).isEqualTo(Account.AppRole.APP_ADMIN);
        verify(accountRepository).save(existing);
    }
}
