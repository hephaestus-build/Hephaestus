package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery.IdentityLinkView;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentAccountUsersTest extends BaseUnitTest {
    private AccountIdentityQuery identities;
    private UserRepository users;
    private CurrentAccountUsers resolver;

    @BeforeEach
    void setUp() {
        identities = mock(AccountIdentityQuery.class);
        users = mock(UserRepository.class);
        resolver = new CurrentAccountUsers(identities, users);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static IdentityLinkView link(long provider, String subject) {
        return new IdentityLinkView(1L, provider, subject, "old-login", null, null, null, 999L, null);
    }

    private static User user(long id, String login) {
        User user = new User();
        user.setId(id);
        user.setLogin(login);
        return user;
    }

    @Test
    void shouldResolveRenamedUserBySubjectWhenUsernameAndActorReferenceAreStale() {
        when(identities.activeLinksForAccount(42L)).thenReturn(List.of(link(1L, "123")));
        when(users.findByNativeIdAndProviderId(123L, 1L)).thenReturn(Optional.of(user(7L, "new-login")));

        assertThat(resolver.resolve(42L)).extracting(User::getLogin).containsExactly("new-login");
    }

    @Test
    void shouldKeepEqualSubjectsOnDifferentProvidersSeparate() {
        when(identities.activeLinksForAccount(42L)).thenReturn(List.of(link(1L, "123"), link(2L, "123")));
        when(users.findByNativeIdAndProviderId(123L, 1L)).thenReturn(Optional.of(user(7L, "alice")));
        when(users.findByNativeIdAndProviderId(123L, 2L)).thenReturn(Optional.of(user(8L, "alice")));

        assertThat(resolver.resolve(42L)).extracting(User::getId).containsExactly(7L, 8L);
    }

    @Test
    void shouldNotUseReassignedUsernameWhenVerifiedSubjectHasNoActor() {
        when(identities.activeLinksForAccount(42L)).thenReturn(List.of(link(1L, "123")));
        when(users.findByNativeIdAndProviderId(123L, 1L)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(42L)).isEmpty();
    }

    @Test
    void shouldDeduplicateActorsAcrossRepeatedLinks() {
        when(identities.activeLinksForAccount(42L)).thenReturn(List.of(link(1L, "123"), link(1L, "123")));
        when(users.findByNativeIdAndProviderId(123L, 1L)).thenReturn(Optional.of(user(7L, "alice")));

        assertThat(resolver.resolve(42L)).extracting(User::getId).containsExactly(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"U123", "", "0", "-1", "9223372036854775808"})
    void shouldNotResolveNonScmOrInvalidSubjects(String subject) {
        when(identities.activeLinksForAccount(42L)).thenReturn(List.of(link(1L, subject)));

        assertThat(resolver.resolve(42L)).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void shouldResolveAuthenticatedAccountWithoutDependingOnLoginClaim() {
        SecurityContextHolder.setContext(
                MockSecurityContextUtils.createSecurityContext("someone-else", "42", new String[0], "token"));
        when(identities.activeLinksForAccount(42L)).thenReturn(List.of(link(1L, "123")));
        when(users.findByNativeIdAndProviderId(123L, 1L)).thenReturn(Optional.of(user(7L, "alice")));

        assertThat(resolver.resolve()).extracting(User::getId).containsExactly(7L);
    }

    @Test
    void shouldNotFallBackToLoginWhenAccountHasNoActiveLinks() {
        SecurityContextHolder.setContext(
                MockSecurityContextUtils.createSecurityContext("alice", "42", new String[0], "token"));
        when(identities.activeLinksForAccount(42L)).thenReturn(List.of());

        assertThat(resolver.resolve()).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void shouldResolveNothingWithoutAuthenticatedAccount() {
        assertThat(resolver.resolve()).isEmpty();
        verifyNoInteractions(users, identities);
    }
}
