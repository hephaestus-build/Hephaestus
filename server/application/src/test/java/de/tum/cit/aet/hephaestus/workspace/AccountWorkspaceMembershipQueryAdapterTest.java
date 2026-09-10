package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountWorkspaceMembershipQueryAdapterTest extends BaseUnitTest {
    @Test
    void shouldReturnMembershipWithoutScmAttributionWhenAccountHasNoScmIdentity() {
        var accounts = mock(CurrentAccountUsers.class);
        var memberships = mock(WorkspaceAccountMembershipRepository.class);
        var actors = mock(WorkspaceMembershipRepository.class);
        var workspace = new Workspace();
        workspace.setId(1L);
        workspace.setWorkspaceSlug("organization");
        workspace.setDisplayName("Organization");
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(42L);
        when(accounts.resolve(42L)).thenReturn(List.of());
        when(memberships.findActiveByAccountId(42L)).thenReturn(List.of(membership));

        assertThat(new AccountWorkspaceMembershipQueryAdapter(memberships, accounts, actors).membershipsForAccount(42L))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.workspaceId()).isEqualTo(1L);
                    assertThat(view.role()).isEqualTo("MEMBER");
                    assertThat(view.memberId()).isNull();
                });
        verifyNoInteractions(actors);
    }

    @Test
    void shouldNotReportAnEmptyMembershipListWhenDatabaseReadFails() {
        var accounts = mock(CurrentAccountUsers.class);
        var memberships = mock(WorkspaceAccountMembershipRepository.class);
        var actors = mock(WorkspaceMembershipRepository.class);
        when(memberships.findActiveByAccountId(42L)).thenThrow(new IllegalStateException("Database unavailable"));
        assertThatThrownBy(() -> new AccountWorkspaceMembershipQueryAdapter(memberships, accounts, actors)
                        .membershipsForAccount(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database unavailable");
    }
}
