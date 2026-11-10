package de.tum.cit.aet.hephaestus.workspace.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry.ActorProfile;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.mock.env.MockEnvironment;

class MembershipAutoSeederTest extends BaseUnitTest {

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private MembershipService membershipService;

    @Mock
    private ConnectionService connectionService;

    @Test
    void shouldNotInspectOrModifyMembershipsWhenDisabled() {
        MembershipAutoSeeder seeder = new MembershipAutoSeeder(
                membershipRepository, membershipService, connectionService, false, new MockEnvironment());

        assertThat(seeder.seedFirstUserWhenEmpty(TestEntities.workspace(42L), List.of(actor())))
                .isEmpty();
        verifyNoInteractions(membershipRepository, membershipService, connectionService);
    }

    @Test
    void shouldNotGrantAdminWhenWorkspaceAlreadyHasMemberships() {
        Workspace workspace = TestEntities.workspace(42L);
        when(membershipRepository.countByWorkspace_Id(42L)).thenReturn(1L);
        MembershipAutoSeeder seeder = new MembershipAutoSeeder(
                membershipRepository, membershipService, connectionService, true, new MockEnvironment());

        var user = actor();
        assertThat(seeder.seedFirstUserWhenEmpty(workspace, List.of(user))).isEmpty();
        verifyNoInteractions(membershipService);
    }

    @Test
    void shouldRefuseToStartEnabledUnderTheProdProfile() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new MembershipAutoSeeder(
                        membershipRepository, membershipService, connectionService, true, prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auto-seed-membership");
    }

    @Test
    void shouldStartDisabledUnderTheProdProfile() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        var seeder = new MembershipAutoSeeder(membershipRepository, membershipService, connectionService, false, prod);

        assertThat(seeder.seedFirstUserWhenEmpty(TestEntities.workspace(42L), List.of(actor())))
                .isEmpty();
    }

    private static ActorProfile actor() {
        return new ActorProfile(7L, 1L, "GITHUB", "https://github.com", "7", "developer", "Developer", true);
    }
}
