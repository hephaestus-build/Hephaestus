package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit coverage of {@link AccountService} guard logic ({@code unlinkIdentity} last-identity/ownership
 * rules and {@code adminSetRole} last-admin/self-demotion rules) with all repository/audit
 * collaborators mocked — including the post-lock race branch ({@code deleteByIdAndAccountId == 0}),
 * which the integration test cannot reach. Uses a real {@link AuthEventLogger} over a mock
 * {@link AuthEventWriter} so "did we audit?" is a single {@code write(...)} verification.
 */
class AccountServiceTest extends BaseUnitTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final IdentityLinkRepository identityLinkRepository = mock(IdentityLinkRepository.class);
    private final IssuedJwtRepository issuedJwtRepository = mock(IssuedJwtRepository.class);
    private final AuthEventWriter auditWriter = mock(AuthEventWriter.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final LoginProviderService loginProviderService = mock(LoginProviderService.class);
    private final GitProviderRegistry gitProviderRegistry = mock(GitProviderRegistry.class);
    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepository,
                identityLinkRepository,
                issuedJwtRepository,
                new AuthEventLogger(auditWriter),
                clock,
                loginProviderService,
                gitProviderRegistry,
                List.of());
        LoginProvider github = new LoginProvider();
        github.setType(LoginProvider.ProviderType.GITHUB);
        github.setBaseUrl("https://github.com");
        lenient().when(loginProviderService.listEnabled()).thenReturn(List.of(github));
        lenient().when(gitProviderRegistry.providerTypeName(anyLong())).thenReturn("GITHUB");
        lenient().when(gitProviderRegistry.providerServerUrl(anyLong())).thenReturn("https://github.com");
    }

    private static IdentityLink link(long id, long gitProviderId) {
        // Real owned entity, not a mock: stubbing getId()/getProviderId() would test the stub, not
        // the service, and couple to getter names. IdentityLink is @NoArgsConstructor + @Setter.
        IdentityLink il = new IdentityLink();
        il.setId(id);
        il.setProviderId(gitProviderId);
        return il;
    }

    @Test
    void unlinkSecondaryIdentityDeletesItAndAudits() {
        List<IdentityLink> active = List.of(link(10L, 100L), link(11L, 101L));
        when(identityLinkRepository.findActiveByAccountIdForUpdate(1L)).thenReturn(active);
        when(identityLinkRepository.deleteByIdAndAccountId(10L, 1L)).thenReturn(1);

        service.unlinkIdentity(1L, 10L, /* actingAccountId */ null);

        verify(identityLinkRepository).deleteByIdAndAccountId(10L, 1L);
        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(auditWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.IDENTITY_UNLINKED);
        // Self-service unlink: no impersonating operator, so no acting account is recorded.
        assertThat(event.getValue().actingAccountId()).isNull();
    }

    @Test
    void unlinkUnderImpersonationAttributesTheOperatorAsActingAccount() {
        List<IdentityLink> active = List.of(link(10L, 100L), link(11L, 101L));
        when(identityLinkRepository.findActiveByAccountIdForUpdate(1L)).thenReturn(active);
        when(identityLinkRepository.deleteByIdAndAccountId(10L, 1L)).thenReturn(1);

        service.unlinkIdentity(1L, 10L, /* actingAccountId = impersonating operator */ 7L);

        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(auditWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.IDENTITY_UNLINKED);
        assertThat(event.getValue().accountId()).isEqualTo(1L);
        assertThat(event.getValue().actingAccountId()).isEqualTo(7L);
    }

    @Test
    void cannotUnlinkTheOnlyRemainingIdentity() {
        List<IdentityLink> active = List.of(link(10L, 100L));
        when(identityLinkRepository.findActiveByAccountIdForUpdate(1L)).thenReturn(active);

        assertThatThrownBy(() -> service.unlinkIdentity(1L, 10L, null))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(identityLinkRepository, never()).deleteByIdAndAccountId(anyLong(), anyLong());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void unlinkingAnIdentityTheAccountDoesNotOwnIs404() {
        List<IdentityLink> active = List.of(link(10L, 100L), link(11L, 101L));
        when(identityLinkRepository.findActiveByAccountIdForUpdate(1L)).thenReturn(active);

        assertThatThrownBy(() -> service.unlinkIdentity(1L, 999L, null))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(identityLinkRepository, never()).deleteByIdAndAccountId(anyLong(), anyLong());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void lostRaceAfterTheGuardSurfacesAs404() {
        List<IdentityLink> active = List.of(link(10L, 100L), link(11L, 101L));
        when(identityLinkRepository.findActiveByAccountIdForUpdate(1L)).thenReturn(active);
        when(identityLinkRepository.deleteByIdAndAccountId(10L, 1L)).thenReturn(0);

        assertThatThrownBy(() -> service.unlinkIdentity(1L, 10L, null))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(auditWriter);
    }

    private Account accountWithRole(long id, Account.AppRole role) {
        Account account = new Account();
        account.setId(id);
        account.setAppRole(role);
        lenient().when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        lenient().when(accountRepository.findByIdForUpdate(id)).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void grantingAdminToAUserPersistsAndAuditsWithoutAnyLockoutCheck() {
        Account account = accountWithRole(2L, Account.AppRole.USER);

        service.adminSetRole(2L, "APP_ADMIN", 1L);

        assertThat(account.getAppRole()).isEqualTo(Account.AppRole.APP_ADMIN);
        verify(accountRepository).save(account);
        // Dedicated APP_ROLE_CHANGED type so the most security-sensitive mutation stays queryable on
        // the indexed event_type column.
        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(auditWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.APP_ROLE_CHANGED);
        assertThat(event.getValue().details()).contains("\"from\":\"USER\"", "\"to\":\"APP_ADMIN\"");
        verify(accountRepository, never()).findByAppRoleAndStatusForUpdate(any(), any());
        // Promotion does NOT revoke sessions — the new role is picked up on the next silent refresh,
        // so the user is not forced to re-login.
        verify(issuedJwtRepository, never()).revokeAllForAccount(anyLong(), any(), any());
    }

    @Test
    void demotingAnotherAdminSucceedsWhileMoreAdminsRemain() {
        Account account = accountWithRole(2L, Account.AppRole.APP_ADMIN);
        when(accountRepository.findByAppRoleAndStatusForUpdate(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE))
                .thenReturn(List.of(new Account(), new Account()));

        service.adminSetRole(2L, "USER", 1L);

        assertThat(account.getAppRole()).isEqualTo(Account.AppRole.USER);
        verify(accountRepository).save(account);
        verify(auditWriter).write(any());
        // Demotion revokes the stripped admin's live sessions so app_admin authority can't outlive the
        // role change for the token's TTL.
        verify(issuedJwtRepository).revokeAllForAccount(eq(2L), any(), eq(IssuedJwt.RevokedReason.ADMIN_REVOKE));
    }

    @Test
    void unknownRoleIsRejectedWithoutSavingOrAuditing() {
        accountWithRole(2L, Account.AppRole.USER);

        assertThatThrownBy(() -> service.adminSetRole(2L, "BOGUS", 1L))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void cannotRevokeYourOwnAdmin() {
        accountWithRole(1L, Account.AppRole.APP_ADMIN);

        assertThatThrownBy(() -> service.adminSetRole(1L, "USER", 1L))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void cannotRevokeTheLastRemainingActiveAdmin() {
        accountWithRole(2L, Account.AppRole.APP_ADMIN);
        when(accountRepository.findByAppRoleAndStatusForUpdate(Account.AppRole.APP_ADMIN, Account.Status.ACTIVE))
                .thenReturn(List.of(new Account()));

        assertThatThrownBy(() -> service.adminSetRole(2L, "USER", 1L))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void softDeleteMarksDeletingRevokesAllSessionsAndAuditsAccountDeleted() {
        Account account = accountWithRole(2L, Account.AppRole.USER);

        service.softDelete(2L, /* actingAccountId */ null);

        assertThat(account.getStatus()).isEqualTo(Account.Status.DELETING);
        assertThat(account.getDeletedAt()).isEqualTo(clock.instant());
        verify(accountRepository).save(account);
        verify(issuedJwtRepository).revokeAllForAccount(eq(2L), any(), eq(IssuedJwt.RevokedReason.ACCOUNT_DELETED));
        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(auditWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.ACCOUNT_DELETED);
        assertThat(event.getValue().accountId()).isEqualTo(2L);
        // Self-service deletion: the victim acted, so no separate operator is attributed.
        assertThat(event.getValue().actingAccountId()).isNull();
    }

    @Test
    void softDeleteIsIdempotentForAnAlreadyDeletingAccount() {
        Account account = accountWithRole(2L, Account.AppRole.USER);
        account.setStatus(Account.Status.DELETING);
        Instant cooldownStart = Instant.parse("2025-12-01T00:00:00Z");
        account.setDeletedAt(cooldownStart);

        service.softDelete(2L, null);

        assertThat(account.getDeletedAt()).isEqualTo(cooldownStart);
        verify(accountRepository, never()).save(any());
        verify(issuedJwtRepository, never()).revokeAllForAccount(anyLong(), any(), any());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void softDeleteUnderImpersonationAttributesTheOperatorSoItIsNotMisreadAsSelfDeletion() {
        accountWithRole(2L, Account.AppRole.USER);

        service.softDelete(2L, /* actingAccountId = impersonating operator */ 1L);

        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(auditWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.ACCOUNT_DELETED);
        assertThat(event.getValue().accountId()).isEqualTo(2L);
        assertThat(event.getValue().actingAccountId()).isEqualTo(1L);
    }

    @Test
    void adminRevokeAllSessionsRevokesAndAuditsJwtRevokedWithAttribution() {
        accountWithRole(2L, Account.AppRole.USER); // requireById target exists
        when(issuedJwtRepository.revokeAllForAccount(eq(2L), any(), eq(IssuedJwt.RevokedReason.ADMIN_REVOKE)))
                .thenReturn(3);

        int revoked = service.adminRevokeAllSessions(2L, 1L);

        assertThat(revoked).isEqualTo(3);
        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(auditWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.JWT_REVOKED);
        assertThat(event.getValue().accountId()).isEqualTo(2L);
        assertThat(event.getValue().actingAccountId()).isEqualTo(1L);
        assertThat(event.getValue().details()).contains("ADMIN_REVOKE", "\"count\":3");
    }

    @Test
    void shouldRefuseToUnlinkTheOnlySignInWhenAnotherLinkIsSlack() {
        when(identityLinkRepository.findActiveByAccountIdForUpdate(1L))
                .thenReturn(List.of(link(10L, 100L), link(11L, 101L)));
        when(gitProviderRegistry.providerTypeName(101L)).thenReturn("SLACK");
        when(gitProviderRegistry.providerServerUrl(101L)).thenReturn("https://slack.com");

        assertThatThrownBy(() -> service.unlinkIdentity(1L, 10L, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only sign-in method");
        verify(identityLinkRepository, never()).deleteByIdAndAccountId(anyLong(), anyLong());
    }

    @Test
    void shouldKeepTheCurrentSignInWhenTheLinkedOrganizationalRealmIsNotAvailable() {
        when(identityLinkRepository.findActiveByAccountIdForUpdate(1L))
                .thenReturn(List.of(link(10L, 100L), link(11L, 101L)));
        when(gitProviderRegistry.providerTypeName(101L)).thenReturn("OIDC");
        when(gitProviderRegistry.providerServerUrl(101L)).thenReturn("https://identity.example.com/realms/old");
        LoginProvider otherRealm = new LoginProvider();
        otherRealm.setType(LoginProvider.ProviderType.OIDC);
        otherRealm.setBaseUrl("https://identity.example.com/realms/new");
        when(loginProviderService.listEnabled()).thenReturn(List.of(otherRealm));

        assertThatThrownBy(() -> service.unlinkIdentity(1L, 10L, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only sign-in method");
        verify(identityLinkRepository, never()).deleteByIdAndAccountId(anyLong(), anyLong());
    }

    @Test
    void shouldAllowUnlinkingAnScmIdentityWhenTheExactOrganizationalIssuerRemainsAvailable() {
        when(identityLinkRepository.findActiveByAccountIdForUpdate(1L))
                .thenReturn(List.of(link(10L, 100L), link(11L, 101L)));
        when(gitProviderRegistry.providerTypeName(101L)).thenReturn("OIDC");
        when(gitProviderRegistry.providerServerUrl(101L)).thenReturn("https://identity.example.com/realms/team/");
        LoginProvider organization = new LoginProvider();
        organization.setType(LoginProvider.ProviderType.OIDC);
        organization.setBaseUrl("https://identity.example.com/realms/team/");
        when(loginProviderService.listEnabled()).thenReturn(List.of(organization));
        when(identityLinkRepository.deleteByIdAndAccountId(10L, 1L)).thenReturn(1);

        service.unlinkIdentity(1L, 10L, null);

        verify(identityLinkRepository).deleteByIdAndAccountId(10L, 1L);
    }
}
