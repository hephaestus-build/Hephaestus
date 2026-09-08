package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class WorkspaceQueryServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceAccountMembershipRepository workspaceMembershipRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private ConnectionService connectionService;

    private WorkspaceQueryService newService() {
        return new WorkspaceQueryService(
                workspaceRepository,
                workspaceMembershipRepository,
                repositoryToMonitorRepository,
                connectionService,
                new WorkspaceProperties(false, null, false, null, WorkspaceProperties.CreationPolicy.ADMIN_ONLY),
                List.of());
    }

    @Test
    void findAccessibleWorkspacesSortsByDisplayNameAndDeduplicatesMemberships() {
        Workspace alphaWorkspace = workspace(1L, "alpha-space", "Alpha Workspace", true);
        Workspace bravoWorkspace = workspace(2L, "bravo-space", "Bravo Workspace", false);
        Workspace zuluWorkspace = workspace(3L, "zulu-space", "Zulu Workspace", true);

        User currentUser = new User();
        currentUser.setId(42L);

        WorkspaceAccountMembership bravoMembership = membership(bravoWorkspace);
        WorkspaceAccountMembership alphaMembership = membership(alphaWorkspace);

        WorkspaceQueryService service = newService();

        when(workspaceRepository.findByStatusAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus.ACTIVE))
                .thenReturn(List.of(zuluWorkspace, alphaWorkspace));
        when(workspaceMembershipRepository.findActiveByAccountId(42L))
                .thenReturn(List.of(bravoMembership, alphaMembership));

        List<Workspace> workspaces;
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentAccountId).thenReturn(Optional.of(42L));
            workspaces = service.findAccessibleWorkspaces();
        }

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

        List<Workspace> workspaces = service.findAccessibleWorkspaces();

        assertThat(workspaces).extracting(Workspace::getWorkspaceSlug).containsExactly("alpha-space", "zulu-space");
    }

    @Test
    void shouldReturnAccountMembershipEvenWithoutAnScmIdentity() {
        Workspace workspace = workspace(1L, "organization-space", "Organization", false);
        when(workspaceRepository.findByStatusAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus.ACTIVE))
                .thenReturn(List.of());
        when(workspaceMembershipRepository.findActiveByAccountId(42L)).thenReturn(List.of(membership(workspace)));
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentAccountId).thenReturn(Optional.of(42L));
            assertThat(newService().findAccessibleWorkspaces())
                    .extracting(Workspace::getWorkspaceSlug)
                    .containsExactly("organization-space");
        }
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

    private WorkspaceAccountMembership membership(Workspace workspace) {
        WorkspaceAccountMembership membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(42L);
        return membership;
    }
}
