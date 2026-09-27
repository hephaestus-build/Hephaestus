package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountWorkspaceMembershipQueryAdapterTest extends BaseUnitTest {
    @Test
    void shouldNotQueryMembershipsWhenAccountHasNoVerifiedActors() {
        var accounts = mock(CurrentAccountUsers.class);
        var memberships = mock(WorkspaceMembershipRepository.class);
        when(accounts.resolve(42L)).thenReturn(List.of());

        assertThat(new AccountWorkspaceMembershipQueryAdapter(
                                memberships,
                                accounts,
                                mock(AccountIdentityQuery.class),
                                mock(WorkspaceActorSelector.class))
                        .membershipsForAccount(42L))
                .isEmpty();
        verifyNoInteractions(memberships);
    }

    @Test
    void shouldNotReportAnEmptyMembershipListWhenDatabaseReadFails() {
        var accounts = mock(CurrentAccountUsers.class);
        var memberships = mock(WorkspaceMembershipRepository.class);
        var actor = new User();
        actor.setId(7L);
        when(accounts.resolve(42L)).thenReturn(List.of(actor));
        when(memberships.findAllWithWorkspaceByUserIdIn(List.of(7L)))
                .thenThrow(new IllegalStateException("Database unavailable"));

        assertThatThrownBy(() -> new AccountWorkspaceMembershipQueryAdapter(
                                memberships,
                                accounts,
                                mock(AccountIdentityQuery.class),
                                mock(WorkspaceActorSelector.class))
                        .membershipsForAccount(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database unavailable");
    }

    @Test
    void shouldResolveTheMemberAccountThroughTheProviderIdentityWhenTheUserIsAMember() {
        var memberships = mock(WorkspaceMembershipRepository.class);
        var identities = mock(AccountIdentityQuery.class);
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(5L);
        var member = new User();
        member.setId(7L);
        member.setNativeId(123L);
        member.setProvider(provider);
        var membership = new WorkspaceMembership();
        membership.setUser(member);
        when(memberships.findByWorkspace_IdAndUser_Id(3L, 7L)).thenReturn(Optional.of(membership));
        when(identities.resolveActiveAccountId(5L, "123", null)).thenReturn(Optional.of(42L));

        assertThat(new AccountWorkspaceMembershipQueryAdapter(
                                memberships,
                                mock(CurrentAccountUsers.class),
                                identities,
                                mock(WorkspaceActorSelector.class))
                        .activeAccountIdForMember(3L, 7L))
                .contains(42L);
    }

    @Test
    void shouldResolveNoAccountWhenTheUserIsNotAMember() {
        var memberships = mock(WorkspaceMembershipRepository.class);
        var identities = mock(AccountIdentityQuery.class);
        when(memberships.findByWorkspace_IdAndUser_Id(3L, 7L)).thenReturn(Optional.empty());

        assertThat(new AccountWorkspaceMembershipQueryAdapter(
                                memberships,
                                mock(CurrentAccountUsers.class),
                                identities,
                                mock(WorkspaceActorSelector.class))
                        .activeAccountIdForMember(3L, 7L))
                .isEmpty();
        verifyNoInteractions(identities);
    }
}
