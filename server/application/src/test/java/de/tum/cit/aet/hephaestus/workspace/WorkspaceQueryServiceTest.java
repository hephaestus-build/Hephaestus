package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class WorkspaceQueryServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMembershipRepository workspaceMembershipRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private CurrentAccountUsers currentAccountUsers;

    @Mock
    private ConnectionService connectionService;

    private WorkspaceQueryService newService() {
        return new WorkspaceQueryService(
                workspaceRepository,
                workspaceMembershipRepository,
                repositoryToMonitorRepository,
                currentAccountUsers,
                connectionService,
                new WorkspaceProperties(false, null, false, null, WorkspaceProperties.CreationPolicy.ADMIN_ONLY),
                new WorkspaceSubdomainProperties(false, ""),
                "http://localhost:4200",
                List.of());
    }

    @Test
    void findAccessibleWorkspacesSortsByDisplayNameAndDeduplicatesMemberships() {
        Workspace alphaWorkspace = workspace(1L, "alpha-space", "Alpha Workspace", true);
        Workspace bravoWorkspace = workspace(2L, "bravo-space", "Bravo Workspace", false);
        Workspace zuluWorkspace = workspace(3L, "zulu-space", "Zulu Workspace", true);

        User currentUser = new User();
        currentUser.setId(42L);

        WorkspaceMembership bravoMembership = membership(bravoWorkspace);
        WorkspaceMembership alphaMembership = membership(alphaWorkspace);

        WorkspaceQueryService service = newService();

        when(workspaceRepository.findByStatusAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus.ACTIVE))
                .thenReturn(List.of(zuluWorkspace, alphaWorkspace));
        when(workspaceMembershipRepository.findByUser_IdIn(Set.of(42L)))
                .thenReturn(List.of(bravoMembership, alphaMembership));
        when(workspaceRepository.findAllById(List.of(2L, 1L))).thenReturn(List.of(bravoWorkspace, alphaWorkspace));

        List<Workspace> workspaces = service.findAccessibleWorkspaces(List.of(currentUser));

        assertThat(workspaces)
                .extracting(Workspace::getWorkspaceSlug)
                .containsExactly("alpha-space", "bravo-space", "zulu-space");
    }

    @Test
    void findAccessibleWorkspacesSortsPublicWorkspacesForAnonymousUsers() {
        Workspace zuluWorkspace = workspace(3L, "zulu-space", "Zulu Workspace", true);
        Workspace alphaWorkspace = workspace(1L, "alpha-space", "Alpha Workspace", true);

        WorkspaceQueryService service = newService();

        when(workspaceRepository.findByStatusAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus.ACTIVE))
                .thenReturn(List.of(zuluWorkspace, alphaWorkspace));

        List<Workspace> workspaces = service.findAccessibleWorkspaces(List.<User>of());

        assertThat(workspaces).extracting(Workspace::getWorkspaceSlug).containsExactly("alpha-space", "zulu-space");
    }

    @Test
    void findAccessibleWorkspacesUnionsMembershipsAcrossAllLinkedIdentities() {
        // The account is signed in via GitLab (user 103, no membership) but also links a GitHub identity
        // (user 2) that IS a workspace member. The accessible list must include that workspace.
        Workspace githubWorkspace = workspace(1L, "gh-space", "GitHub Workspace", false);

        User gitlabIdentity = new User();
        gitlabIdentity.setId(103L);
        User githubIdentity = new User();
        githubIdentity.setId(2L);

        WorkspaceQueryService service = newService();

        when(currentAccountUsers.resolve()).thenReturn(List.of(gitlabIdentity, githubIdentity));
        when(workspaceRepository.findByStatusAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus.ACTIVE))
                .thenReturn(List.of());
        when(workspaceMembershipRepository.findByUser_IdIn(Set.of(103L, 2L)))
                .thenReturn(List.of(membership(githubWorkspace)));
        when(workspaceRepository.findAllById(List.of(1L))).thenReturn(List.of(githubWorkspace));

        List<Workspace> workspaces = service.findAccessibleWorkspaces();

        assertThat(workspaces).extracting(Workspace::getWorkspaceSlug).containsExactly("gh-space");
    }

    private Workspace workspace(Long id, String slug, String displayName, boolean publiclyViewable) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(displayName);
        workspace.setIsPubliclyViewable(publiclyViewable);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        return workspace;
    }

    private WorkspaceMembership membership(Workspace workspace) {
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setWorkspace(workspace);
        membership.setId(new WorkspaceMembership.Id(workspace.getId(), 42L));
        return membership;
    }
}
