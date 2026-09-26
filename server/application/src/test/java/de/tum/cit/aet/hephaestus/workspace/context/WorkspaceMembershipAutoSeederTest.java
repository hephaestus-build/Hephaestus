package de.tum.cit.aet.hephaestus.workspace.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class WorkspaceMembershipAutoSeederTest extends BaseUnitTest {

    @Mock
    private WorkspaceMembershipRepository membershipRepository;

    @Mock
    private WorkspaceMembershipService membershipService;

    @Mock
    private ConnectionService connectionService;

    @Test
    void shouldNotInspectOrModifyMembershipsWhenDisabled() {
        WorkspaceMembershipAutoSeeder seeder =
                new WorkspaceMembershipAutoSeeder(membershipRepository, membershipService, connectionService, false);

        assertThat(seeder.seedFirstUserWhenEmpty(TestEntities.workspace(42L), List.of(new User())))
                .isEmpty();
        verifyNoInteractions(membershipRepository, membershipService, connectionService);
    }

    @Test
    void shouldNotGrantAdminWhenWorkspaceAlreadyHasMemberships() {
        Workspace workspace = TestEntities.workspace(42L);
        when(membershipRepository.countByWorkspace_Id(42L)).thenReturn(1L);
        WorkspaceMembershipAutoSeeder seeder =
                new WorkspaceMembershipAutoSeeder(membershipRepository, membershipService, connectionService, true);

        User user = new User();
        user.setId(7L);
        assertThat(seeder.seedFirstUserWhenEmpty(workspace, List.of(user))).isEmpty();
        verifyNoInteractions(membershipService);
    }
}
