package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery.MembershipView;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlackConversationIdentityResolverTest extends BaseUnitTest {
    private GitProviderRegistry providers;
    private AccountIdentityQuery identities;
    private AccountMembershipQuery memberships;
    private AccountSummaryQuery users;
    private SlackConversationIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        providers = mock(GitProviderRegistry.class);
        identities = mock(AccountIdentityQuery.class);
        memberships = mock(AccountMembershipQuery.class);
        users = mock(AccountSummaryQuery.class);
        resolver = new SlackConversationIdentityResolver(providers, identities, memberships, users);
    }

    @Test
    void shouldUseTheVerifiedWorkspaceMemberIdWithoutAUsernameLookup() {
        givenMembership(42L);
        assertThat(resolver.resolveAccountId(42L, "T1", "U1")).contains(7L);
        verifyNoInteractions(users);
    }

    @Test
    void shouldResolveTheVerifiedDeveloperForAnActiveAccount() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveActiveAccountId(5L, "U1", "T1")).thenReturn(Optional.of(7L));
        when(memberships.membershipsForAccount(7L))
                .thenReturn(
                        List.of(new MembershipView(42L, "workspace", "Workspace", AccountMembershipQuery.Role.MEMBER)));
        var actor = new AccountSummaryQuery.AccountSummary(7L, "Renamed account", null);
        when(users.findAllByIds(List.of(7L))).thenReturn(Map.of(7L, actor));

        assertThat(resolver.resolveDeveloper(42L, "T1", "U1")).contains(actor);
    }

    @Test
    void shouldNotAttributeMembershipFromAnotherWorkspace() {
        givenMembership(43L);
        assertThat(resolver.resolveAccountId(42L, "T1", "U1")).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void shouldRejectMissingTeamBeforeResolvingIdentity() {
        assertThat(resolver.resolveAccountId(42L, null, "U1")).isEmpty();
        assertThat(resolver.resolveAccountId(42L, " ", "U1")).isEmpty();
        verifyNoInteractions(providers, identities, memberships, users);
    }

    @Test
    void shouldResolveNothingForAnUnlinkedSlackIdentity() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveAccountId(5L, "U1", "T1")).thenReturn(Optional.empty());

        assertThat(resolver.resolveAccountId(42L, "T1", "U1")).isEmpty();
        verifyNoInteractions(memberships, users);
    }

    @Test
    void shouldResolveOutboundSlackIdentityUsingProviderSubjectAndExactTeam() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.activeLinksForAccount(7L))
                .thenReturn(List.of(
                        new AccountIdentityQuery.IdentityView(1L, 5L, "U-other", "T2"),
                        new AccountIdentityQuery.IdentityView(2L, 5L, "U1", "T1")));
        when(identities.resolveActiveAccountId(5L, "U1", "T1")).thenReturn(Optional.of(7L));
        assertThat(resolver.resolveSlackUserId(7L, "T1")).contains("U1");
        assertThat(resolver.resolveSlackUserId(7L, "T3")).isEmpty();
    }

    @Test
    void shouldRejectOutboundIdentityWhenTeamIsMissing() {
        assertThat(resolver.resolveSlackUserId(314L, null)).isEmpty();
        assertThat(resolver.resolveSlackUserId(314L, " ")).isEmpty();
        verifyNoInteractions(providers, identities, memberships, users);
    }

    @Test
    void shouldNotAdmitConversationInteractionForInactiveAccount() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveActiveAccountId(5L, "U1", "T1")).thenReturn(Optional.empty());
        assertThat(resolver.resolveActiveAccountId(42L, "T1", "U1")).isEmpty();
        verifyNoInteractions(memberships, users);
    }

    @Test
    void shouldResolveTheLinkedAccountWithoutStatusOrMembershipWhenItTurnsANotificationOff() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveAccountId(5L, "U1", "T1")).thenReturn(Optional.of(7L));

        assertThat(resolver.resolveLinkedAccountId("T1", "U1")).contains(7L);
        assertThat(resolver.resolveLinkedAccountId(null, "U1")).isEmpty();
        verifyNoInteractions(memberships, users);
    }

    private void givenMembership(long workspaceId) {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(5L);
        when(identities.resolveAccountId(5L, "U1", "T1")).thenReturn(Optional.of(7L));
        when(memberships.membershipsForAccount(7L))
                .thenReturn(List.of(
                        new MembershipView(workspaceId, "workspace", "Workspace", AccountMembershipQuery.Role.MEMBER)));
    }
}
