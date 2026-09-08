package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountWorkspaceMembershipQueryAdapterTest extends BaseUnitTest {
    @Test
    void shouldNotQueryMembershipsWhenAccountHasNoVerifiedActors() {
        var accounts = mock(CurrentAccountUsers.class);
        var memberships = mock(WorkspaceMembershipRepository.class);
        when(accounts.resolve(42L)).thenReturn(List.of());

        assertThat(new AccountWorkspaceMembershipQueryAdapter(memberships, accounts).membershipsForAccount(42L))
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

        assertThatThrownBy(() ->
                        new AccountWorkspaceMembershipQueryAdapter(memberships, accounts).membershipsForAccount(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database unavailable");
    }
}
