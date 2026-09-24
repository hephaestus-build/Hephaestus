package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeAccountMembershipQueryTest extends BaseUnitTest {
    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    private final AccountIdentityQuery identities = mock(AccountIdentityQuery.class);
    private final AccountWorkspaceMembershipQueryAdapter query =
            new AccountWorkspaceMembershipQueryAdapter(memberships, identities);

    @Test
    void shouldNotConfuseMatchingUsernamesAcrossProviderIdentities() {
        when(identities.activeLinksForAccount(41L)).thenReturn(List.of(identity(1L, 501L)));
        when(identities.activeLinksForAccount(42L)).thenReturn(List.of(identity(2L, 502L)));
        var workspace = new Workspace();
        workspace.setId(7L);
        workspace.setWorkspaceSlug("native-membership");
        workspace.setDisplayName("Native membership");
        var actor = new User();
        actor.setId(501L);
        var membership = mock(WorkspaceMembership.class);
        when(membership.getWorkspace()).thenReturn(workspace);
        when(membership.getUser()).thenReturn(actor);
        when(membership.getRole()).thenReturn(WorkspaceMembership.WorkspaceRole.ADMIN);
        when(memberships.findByUser_IdIn(List.of(501L))).thenReturn(List.of(membership));
        when(memberships.findByUser_IdIn(List.of(502L))).thenReturn(List.of());

        assertThat(query.isAdministrator(7L, 41L)).isTrue();
        assertThat(query.isAdministrator(8L, 41L)).isFalse();
        assertThat(query.isAdministrator(7L, 42L)).isFalse();
        verify(memberships, never()).findAllWithWorkspaceByUserLoginInLowercase(any());
    }

    private static AccountIdentityQuery.IdentityLinkView identity(long provider, long actor) {
        return new AccountIdentityQuery.IdentityLinkView(
                provider, provider, "same-native-subject", "same-login", null, null, null, actor, null);
    }
}
