package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider.ProviderType;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Pins the security-relevant provisioning behavior: verified-email stamping on JIT create, and
 * account-linking that binds to the current account and rejects cross-account collisions.
 */
@Tag("unit")
class AccountProvisioningServiceTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final long PROVIDER_ID = 5L;

    private AccountRepository accountRepository;
    private IdentityRepository identityRepository;
    private VerifiedEmailResolver verifiedEmailResolver;
    private AccountJitCreator accountJitCreator;
    private AdminBootstrapPolicy adminBootstrapPolicy;
    private LoginProviderRepository loginProviderRepository;
    private GitProviderRegistry gitProviderRegistry;
    private final PersonDataWriteFence writeFence = mock(PersonDataWriteFence.class);
    private final PeopleRegistry people = mock(PeopleRegistry.class);
    private AccountProvisioningService service;
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    @BeforeEach
    void setUp() {
        lenient().when(writeFence.holdForWrite(any())).thenReturn(true);
        accountRepository = mock(AccountRepository.class);
        lenient().when(accountRepository.lockStatusForUpdate(anyLong())).thenReturn(Optional.of("ACTIVE"));
        identityRepository = mock(IdentityRepository.class);
        gitProviderRegistry = mock(GitProviderRegistry.class);
        verifiedEmailResolver = mock(VerifiedEmailResolver.class);
        accountJitCreator = mock(AccountJitCreator.class);
        adminBootstrapPolicy = mock(AdminBootstrapPolicy.class);
        loginProviderRepository = mock(LoginProviderRepository.class);
        var githubProvider = new LoginProvider();
        githubProvider.setRegistrationId("github");
        githubProvider.setType(LoginProvider.ProviderType.GITHUB);
        githubProvider.setBaseUrl("https://github.com");
        lenient().when(loginProviderRepository.findByRegistrationId(any())).thenReturn(Optional.of(githubProvider));
        lenient().when(gitProviderRegistry.resolveProviderId(any(), any())).thenReturn(PROVIDER_ID);
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountJitCreator.create(any(), any())).thenAnswer(inv -> {
            Account created = inv.getArgument(0);
            created.setId(101L);
            return created;
        });
        service = new AccountProvisioningService(
                accountRepository,
                identityRepository,
                gitProviderRegistry,
                loginProviderRepository,
                verifiedEmailResolver,
                accountJitCreator,
                adminBootstrapPolicy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                events,
                writeFence,
                people);
    }

    @Test
    void linkingNewNativeIdentityToDeletingAccountIsRejectedBeforeWriting() {
        when(accountRepository.lockStatusForUpdate(42L)).thenReturn(Optional.of("DELETING"));

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "github", "new-native-subject", principal(), AuthIntentCookie.Intent.link(42L, null)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(exception.getError().getErrorCode())
                                .isEqualTo("identity_processing_suppressed"));

        verify(identityRepository, never()).save(any());
        verify(accountRepository, never()).findById(anyLong());
        verify(accountJitCreator, never()).create(any(), any());
    }

    @Test
    void slackLoginMode_rejectedBecauseSlackIsLinkOnly() {
        useSlackProvider();

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "slack", "U123", principal(), AuthIntentCookie.Intent.login(null, null)))
                .isInstanceOf(LinkOnlyProviderLoginException.class);

        verify(accountJitCreator, never()).create(any(), any());
        verify(identityRepository, never()).save(any());
    }

    @Test
    void slackLink_withoutTeamId_failsClosed() {
        useSlackProvider();

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "slack", "U123", principal(), AuthIntentCookie.Intent.link(42L, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team_id");

        verify(identityRepository, never()).save(any());
    }

    @Test
    void outlineLoginMode_rejectedBecauseOutlineIsLinkOnly() {
        useOutlineProvider();

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "outline", "0aa1bb2c-user", principal(), AuthIntentCookie.Intent.login(null, null)))
                .isInstanceOf(LinkOnlyProviderLoginException.class);

        verify(accountJitCreator, never()).create(any(), any());
        verify(identityRepository, never()).save(any());
    }

    @Test
    void outlineLink_withoutTeamId_failsClosed() {
        // An Outline user UUID is only unique within its team; a null team_id would alias
        // identities
        // across tenants of a shared instance — the guard must fail closed, exactly like Slack.
        useOutlineProvider();

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "outline", "0aa1bb2c-user", principal(), AuthIntentCookie.Intent.link(42L, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team_id");

        verify(identityRepository, never()).save(any());
    }

    @Test
    void outlineLink_withFlatTeamId_bindsToCurrentAccountWithTeamKey() {
        useOutlineProvider();
        afterVerifiedClaim("0aa1bb2c-user", "9ff8ee7d-team");
        OAuth2User outlinePrincipal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", "0aa1bb2c-user", "name", "Ada", "team_id", "9ff8ee7d-team"),
                "id");

        var result = service.resolveOrProvision(
                "outline", "0aa1bb2c-user", outlinePrincipal, AuthIntentCookie.Intent.link(42L, null));

        assertThat(result.account().getId()).isEqualTo(42L);
        assertThat(result.identityLinked()).isTrue();
        ArgumentCaptor<Identity> saved = ArgumentCaptor.forClass(Identity.class);
        verify(identityRepository).save(saved.capture());
        assertThat(saved.getValue().getProviderUserKey()).isEqualTo("0aa1bb2c-user");
        assertThat(saved.getValue().getTeamKey()).isEqualTo("9ff8ee7d-team");
        assertThat(saved.getValue().getLinkedVia()).isEqualTo(Identity.LinkedVia.MANUAL);
    }

    @Test
    void jitCreate_whenOnBootstrapAllowlist_promotesToAppAdmin() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.empty());
        when(verifiedEmailResolver.resolve(eq("github"), any()))
                .thenReturn(new VerifiedEmailResolver.ResolvedEmail("u@v.de", true));
        when(adminBootstrapPolicy.shouldPromote(eq("github"), eq("sub-1"), any()))
                .thenReturn(true);

        var result =
                service.resolveOrProvision("github", "sub-1", principal(), AuthIntentCookie.Intent.login(null, null));

        assertThat(result.account().getAppRole()).isEqualTo(Account.AppRole.APP_ADMIN);
        assertThat(result.identityLinked())
                .as("a JIT login is not an identity-link")
                .isFalse();
    }

    @Test
    void jitCreate_whenNotOnBootstrapAllowlist_staysUser() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.empty());
        when(verifiedEmailResolver.resolve(eq("github"), any()))
                .thenReturn(new VerifiedEmailResolver.ResolvedEmail("u@v.de", true));

        var result =
                service.resolveOrProvision("github", "sub-1", principal(), AuthIntentCookie.Intent.login(null, null));

        assertThat(result.account().getAppRole()).isEqualTo(Account.AppRole.USER);
        verify(events, never()).publishEvent(any(AccountSecurityChangedEvent.class));
    }

    private static OAuth2User principal() {
        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "sub-1", "login", "octocat"), "id");
    }

    private void useSlackProvider() {
        var slackProvider = new LoginProvider();
        slackProvider.setRegistrationId("slack");
        slackProvider.setType(LoginProvider.ProviderType.SLACK);
        slackProvider.setBaseUrl("https://slack.com");
        when(loginProviderRepository.findByRegistrationId("slack")).thenReturn(Optional.of(slackProvider));
    }

    private void useOutlineProvider() {
        var outlineProvider = new LoginProvider();
        outlineProvider.setRegistrationId("outline");
        outlineProvider.setType(ProviderType.OUTLINE);
        outlineProvider.setBaseUrl("https://wiki.example.com");
        when(loginProviderRepository.findByRegistrationId("outline")).thenReturn(Optional.of(outlineProvider));
    }

    private static Identity linkOn(Account account) {
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(PROVIDER_ID);
        link.setProviderUserKey("sub-1");
        return link;
    }

    private void afterVerifiedClaim(String subject, String team) {
        var identity = linkOn(accountWithId(42L));
        identity.setProviderUserKey(subject);
        identity.setTeamKey(team);
        when(people.ensureHuman(any(), any())).thenReturn(52L);
        when(accountRepository.findById(42L)).thenReturn(Optional.of(identity.getAccount()));
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq(subject), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(identity));
    }

    private static Account accountWithId(long id) {
        Account a = new Account("Ada");
        a.setId(id);
        return a;
    }

    @Test
    void jitCreate_verifiedEmail_stampsVerifiedAt() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.empty());
        when(verifiedEmailResolver.resolve(eq("github"), any()))
                .thenReturn(new VerifiedEmailResolver.ResolvedEmail("u@v.de", true));

        service.resolveOrProvision("github", "sub-1", principal(), AuthIntentCookie.Intent.login(null, null));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountJitCreator).create(saved.capture(), any());
        assertThat(saved.getValue().getPrimaryEmail()).isEqualTo("u@v.de");
        assertThat(saved.getValue().getPrimaryEmailVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void jitCreate_unverifiedEmail_leavesVerifiedAtNull() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.empty());
        when(verifiedEmailResolver.resolve(eq("github"), any()))
                .thenReturn(new VerifiedEmailResolver.ResolvedEmail("u@v.de", false));

        service.resolveOrProvision("github", "sub-1", principal(), AuthIntentCookie.Intent.login(null, null));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountJitCreator).create(saved.capture(), any());
        assertThat(saved.getValue().getPrimaryEmail()).isEqualTo("u@v.de");
        assertThat(saved.getValue().getPrimaryEmailVerifiedAt()).isNull();
    }

    @Test
    void shouldReuseTheAccountResolvedBySerializedJitCreationWhenRosterClaimWins() {
        Account winner = accountWithId(7L);
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.empty());
        when(verifiedEmailResolver.resolve(eq("github"), any()))
                .thenReturn(new VerifiedEmailResolver.ResolvedEmail("u@v.de", true));
        doReturn(winner).when(accountJitCreator).create(any(), any());

        var result =
                service.resolveOrProvision("github", "sub-1", principal(), AuthIntentCookie.Intent.login(null, null));

        assertThat(result.account().getId()).isEqualTo(7L);
        assertThat(result.identityLinked()).isFalse();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void shouldPropagatePersistenceFailureWithoutInventingAConcurrentWinner() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.empty());
        when(verifiedEmailResolver.resolve(eq("github"), any()))
                .thenReturn(new VerifiedEmailResolver.ResolvedEmail("u@v.de", true));
        doThrow(new DataIntegrityViolationException("invalid persisted identity"))
                .when(accountJitCreator)
                .create(any(), any());

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "github", "sub-1", principal(), AuthIntentCookie.Intent.login(null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("invalid persisted identity");
    }

    @Test
    void shouldUseExactAccountOwnershipWhenTheProviderProfileChanges() {
        existingLink();

        service.resolveOrProvision("github", "777", principal(), AuthIntentCookie.Intent.login(null, null));

        verify(identityRepository).touchLastLogin(31L, NOW);
    }

    @Test
    void returningLoginDoesNotUpdateADeletingAccount() {
        existingLink();
        when(accountRepository.lockStatusForUpdate(9L)).thenReturn(Optional.of("DELETING"));

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "github", "777", principal(), AuthIntentCookie.Intent.login(null, null)))
                .isInstanceOf(OAuth2AuthenticationException.class);

        verify(identityRepository, never()).touchLastLogin(anyLong(), any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void shouldNotReadProviderMirrorsWhenReturningAnOwnedAccount() {
        existingLink();

        service.resolveOrProvision("github", "777", principal(), AuthIntentCookie.Intent.login(null, null));

        verify(gitProviderRegistry, never()).actor(anyLong());
        verify(gitProviderRegistry, never()).accountActors(anyLong());
    }

    private Identity existingLink() {
        Account account = new Account("Existing");
        account.setId(9L);
        Identity link = new Identity();
        link.setId(31L);
        link.setAccount(account);
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("777"), any()))
                .thenReturn(Optional.of(link));
        return link;
    }

    @Test
    void shouldPersistOnlyTheExactIdentityKeyWhenSignInCreatesAnAccount() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("777"), any()))
                .thenReturn(Optional.empty());
        when(verifiedEmailResolver.resolve(eq("github"), any()))
                .thenReturn(new VerifiedEmailResolver.ResolvedEmail("u@v.de", true));

        service.resolveOrProvision("github", "777", principal(), AuthIntentCookie.Intent.login(null, null));

        var link = ArgumentCaptor.forClass(Identity.class);
        verify(accountJitCreator).create(any(), link.capture());
    }

    @Test
    void link_bindsToCurrentAccount() {
        afterVerifiedClaim("sub-1", "");

        var result =
                service.resolveOrProvision("github", "sub-1", principal(), AuthIntentCookie.Intent.link(42L, null));

        assertThat(result.account().getId()).isEqualTo(42L);
        assertThat(result.identityLinked())
                .as("attaching a new identity is an identity-link")
                .isTrue();
        ArgumentCaptor<Identity> saved = ArgumentCaptor.forClass(Identity.class);
        verify(identityRepository).save(saved.capture());
        assertThat(saved.getValue().getLinkedVia()).isEqualTo(Identity.LinkedVia.MANUAL);
        assertThat(saved.getValue().getAccount().getId()).isEqualTo(42L);
        verify(events)
                .publishEvent(
                        new AccountSecurityChangedEvent(42L, AccountSecurityChangedEvent.Kind.IDENTITY_LINKED, NOW));
        verify(accountRepository, never()).save(any()); // link attaches, never JIT-creates
    }

    @Test
    void link_rejectsWhenIdentityAlreadyLinkedElsewhere() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.of(linkOn(accountWithId(99L))));

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "github", "sub-1", principal(), AuthIntentCookie.Intent.link(42L, null)))
                .isInstanceOf(AccountLinkConflictException.class)
                .hasMessageContaining("already linked to a different account");

        verify(identityRepository, never()).save(any());
    }

    @Test
    void link_idempotentWhenAlreadyLinkedToSameAccount() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.of(linkOn(accountWithId(42L))));

        var result =
                service.resolveOrProvision("github", "sub-1", principal(), AuthIntentCookie.Intent.link(42L, null));

        assertThat(result.account().getId()).isEqualTo(42L);
        // Re-affirming an already-linked identity persists NO new link, so it must NOT report a
        // link
        // (the handler would otherwise audit a phantom IDENTITY_LINKED for what is really a login).
        assertThat(result.identityLinked()).isFalse();
        verify(identityRepository).touchLastLogin(any(), any());
        verify(events, never()).publishEvent(any(AccountSecurityChangedEvent.class));
        verify(identityRepository, never()).save(any());
    }

    @Test
    void link_nullBinding_failsClosed() {
        when(identityRepository.findActiveByProviderSubject(eq(PROVIDER_ID), eq("sub-1"), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "github", "sub-1", principal(), AuthIntentCookie.Intent.link(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authenticated account binding");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void erasedProviderIdentity_cannotCreateAnotherAccountOrAttachANewLink() {
        when(writeFence.holdForWrite(List.of(new PersonIdentity(PROVIDER_ID, "42", null))))
                .thenReturn(false);
        assertThatThrownBy(() -> service.resolveOrProvision("github", "42", mock(OAuth2User.class), null))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verify(accountJitCreator, never()).create(any(), any());
        verify(identityRepository, never()).save(any());
    }
}
