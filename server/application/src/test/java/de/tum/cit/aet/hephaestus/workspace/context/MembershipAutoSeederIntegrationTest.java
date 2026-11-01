package de.tum.cit.aet.hephaestus.workspace.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MembershipAutoSeederIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private ConnectionService connectionService;

    @Test
    void shouldGrantAdminToFirstUserWhenEnabledAndWorkspaceIsEmpty() {
        User visitor = persistUser("auto-seed-visitor");
        User owner = persistUser("auto-seed-owner");
        Workspace workspace = createWorkspace("auto-seed", "AutoSeed", "autoseed", AccountType.ORG, owner);
        membershipRepository.deleteAll(membershipRepository.findByWorkspace_Id(workspace.getId()));
        MembershipAutoSeeder seeder =
                new MembershipAutoSeeder(membershipRepository, membershipService, connectionService, true);

        var seeded = seeder.seedFirstUserWhenEmpty(workspace, List.of(actor(visitor)));

        assertThat(seeded).get().extracting(membership -> membership.getRole()).isEqualTo(WorkspaceRole.ADMIN);
        assertThat(membershipRepository.findByWorkspace_Id(workspace.getId()))
                .singleElement()
                .satisfies(membership -> {
                    assertThat(membership.getId().getAccountId()).isEqualTo(accountId(visitor));
                    assertThat(membership.getRole()).isEqualTo(WorkspaceRole.ADMIN);
                });
    }

    @Test
    void shouldGrantAdminToTheVisitorsActorOnTheWorkspacesProvider() {
        User gitLabActor =
                userRepository.save(TestUserFactory.createUser(81_001L, "auto-seed-both", ensureGitLabProvider()));
        User gitHubActor = persistUser("auto-seed-both");
        User owner = persistUser("auto-seed-github-owner");
        Workspace workspace = workspaceService.createWorkspace(
                new CreateWorkspaceRequestDTO(
                        "auto-seed-github",
                        "AutoSeed GitHub",
                        "autoseed-github",
                        AccountType.ORG,
                        IntegrationKind.GITHUB,
                        "ghp-auto-seed",
                        null,
                        null,
                        null),
                owner.getId());
        membershipRepository.deleteAll(membershipRepository.findByWorkspace_Id(workspace.getId()));
        MembershipAutoSeeder seeder =
                new MembershipAutoSeeder(membershipRepository, membershipService, connectionService, true);

        seeder.seedFirstUserWhenEmpty(workspace, List.of(actor(gitLabActor), actor(gitHubActor)));

        assertThat(membershipRepository.findByWorkspace_Id(workspace.getId()))
                .singleElement()
                .satisfies(membership ->
                        assertThat(membership.getId().getAccountId()).isEqualTo(accountId(gitHubActor)));
    }
}
