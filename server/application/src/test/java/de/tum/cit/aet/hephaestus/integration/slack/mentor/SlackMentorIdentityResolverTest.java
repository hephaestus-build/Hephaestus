package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery.WorkspaceMembershipView;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlackMentorIdentityResolverTest extends BaseUnitTest {
    private GitProviderRegistry providers;
    private AccountIdentityQuery identities;
    private AccountWorkspaceMembershipQuery memberships;
    private UserRepository users;
    private SlackMentorIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        providers = mock(GitProviderRegistry.class);
        identities = mock(AccountIdentityQuery.class);
        memberships = mock(AccountWorkspaceMembershipQuery.class);
        users = mock(UserRepository.class);
        resolver = new SlackMentorIdentityResolver(providers, identities, memberships, users);
    }

    @Test
    void shouldUseTheVerifiedWorkspaceMemberIdWithoutAUsernameLookup() {
        givenMembership(42L);
        assertThat(resolver.resolveMemberId(42L, "T1", "U1")).contains(314L);
        verifyNoInteractions(users);
    }

    @Test
    void shouldResolveTheVerifiedDeveloperForAnActiveAccount() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveActiveAccountId(5L, "U1", "T1")).thenReturn(Optional.of(7L));
        when(memberships.membershipsForAccount(7L))
                .thenReturn(List.of(new WorkspaceMembershipView(42L, "workspace", "Workspace", "MEMBER", 314L)));
        var actor = new User();
        actor.setLogin("renamed-login");
        when(users.findById(314L)).thenReturn(Optional.of(actor));

        assertThat(resolver.resolveDeveloper(42L, "T1", "U1")).contains(actor);
    }

    @Test
    void shouldNotAttributeMembershipFromAnotherWorkspace() {
        givenMembership(43L);
        assertThat(resolver.resolveMemberId(42L, "T1", "U1")).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void shouldRejectMissingTeamBeforeResolvingIdentity() {
        assertThat(resolver.resolveMemberId(42L, null, "U1")).isEmpty();
        assertThat(resolver.resolveMemberId(42L, " ", "U1")).isEmpty();
        verifyNoInteractions(providers, identities, memberships, users);
    }

    @Test
    void shouldResolveNothingForAnUnlinkedSlackIdentity() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveAccountId(5L, "U1", "T1")).thenReturn(Optional.empty());

        assertThat(resolver.resolveMemberId(42L, "T1", "U1")).isEmpty();
        verifyNoInteractions(memberships, users);
    }

    @Test
    void shouldResolveOutboundSlackIdentityUsingProviderSubjectAndExactTeam() {
        var provider = new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com");
        provider.setId(9L);
        var actor = new User();
        actor.setProvider(provider);
        actor.setNativeId(123L);
        when(users.findById(314L)).thenReturn(Optional.of(actor));
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveActiveAccountId(9L, "123", null)).thenReturn(Optional.of(7L));
        when(identities.activeLinksForAccount(7L))
                .thenReturn(List.of(
                        new AccountIdentityQuery.IdentityLinkView(
                                1L, 5L, "U-other", null, null, null, null, null, "T2"),
                        new AccountIdentityQuery.IdentityLinkView(2L, 5L, "U1", null, null, null, null, null, "T1")));

        assertThat(resolver.resolveSlackUserId(314L, "T1")).contains("U1");
        when(identities.resolveActiveAccountId(9L, "123", null)).thenReturn(Optional.empty());
        assertThat(resolver.resolveSlackUserId(314L, "T1")).isEmpty();
    }

    @Test
    void shouldRejectOutboundIdentityWhenTeamIsMissing() {
        assertThat(resolver.resolveSlackUserId(314L, null)).isEmpty();
        assertThat(resolver.resolveSlackUserId(314L, " ")).isEmpty();
        verifyNoInteractions(providers, identities, memberships, users);
    }

    @Test
    void shouldNotAdmitMentorInteractionForInactiveAccount() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveActiveAccountId(5L, "U1", "T1")).thenReturn(Optional.empty());
        assertThat(resolver.resolveActiveMemberId(42L, "T1", "U1")).isEmpty();
        verifyNoInteractions(memberships, users);
    }

    private void givenMembership(long workspaceId) {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveAccountId(5L, "U1", "T1")).thenReturn(Optional.of(7L));
        when(memberships.membershipsForAccount(7L))
                .thenReturn(
                        List.of(new WorkspaceMembershipView(workspaceId, "workspace", "Workspace", "MEMBER", 314L)));
    }
}
