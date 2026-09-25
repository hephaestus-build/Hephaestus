package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NativeAccountMembershipQueryTest extends BaseUnitTest {
    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    private final CurrentAccountUsers accountUsers = mock(CurrentAccountUsers.class);
    private final AccountIdentityQuery identities = mock(AccountIdentityQuery.class);
    private final AccountWorkspaceMembershipQueryAdapter query =
            new AccountWorkspaceMembershipQueryAdapter(memberships, accountUsers, identities);

    @Test
    void shouldResolveAdministratorByProviderIdentityRatherThanCachedActorLink() {
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(1L);
        var actor = new User();
        actor.setProvider(provider);
        actor.setNativeId(123L);
        var membership = mock(WorkspaceMembership.class);
        when(membership.getUser()).thenReturn(actor);
        when(membership.getRole()).thenReturn(WorkspaceMembership.WorkspaceRole.ADMIN);
        when(memberships.findAllWithUserByWorkspaceId(7L)).thenReturn(List.of(membership));
        when(identities.resolveActiveAccountId(1L, "123", null)).thenReturn(Optional.of(41L));

        assertThat(query.administratorAccountIds(7L)).containsExactly(41L);
        assertThat(query.isAdministrator(7L, 41L)).isTrue();
        assertThat(query.isAdministrator(7L, 42L)).isFalse();
    }
}
