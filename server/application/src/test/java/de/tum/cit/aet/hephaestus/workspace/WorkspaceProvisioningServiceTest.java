package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.identity.AuthenticatedGitProviderUserService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class WorkspaceProvisioningServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private IdentityProviderRepository gitProviderRepository;

    @Mock
    private WorkspaceMembershipRepository workspaceMembershipRepository;

    @Mock
    private WorkspaceMembershipService workspaceMembershipService;

    @Mock
    private AuthenticatedGitProviderUserService authenticatedGitProviderUserService;

    @Mock
    private ConnectionService connectionService;

    private WorkspaceProvisioningService provisioningService;

    private WorkspaceProperties workspaceProperties;

    @BeforeEach
    void setUp() {
        workspaceProperties = new WorkspaceProperties(
                true,
                new WorkspaceProperties.DefaultProperties("aet-org", "pat-token", List.of()),
                false,
                null,
                WorkspaceProperties.CreationPolicy.SELF_SERVICE);

        provisioningService = new WorkspaceProvisioningService(
                workspaceProperties,
                workspaceRepository,
                repositoryToMonitorRepository,
                workspaceService,
                userRepository,
                gitProviderRepository,
                workspaceMembershipRepository,
                workspaceMembershipService,
                authenticatedGitProviderUserService,
                connectionService,
                List.of(),
                new ScmServerEndpointPolicy(new MockEnvironment()));
    }

    @Test
    void bootstrapDefaultPatWorkspace_addsAdminAsMember() throws Exception {
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("aet-org");
        workspace.setDisplayName("AET Org");
        workspace.setId(1L);

        User owner = new User();
        owner.setId(11L);
        owner.setLogin("aet-org");
        owner.setName("Owner");
        owner.setAvatarUrl("https://example.com/avatar.png");
        owner.setHtmlUrl("https://example.com");
        owner.setType(User.Type.USER);

        User admin = new User();
        admin.setId(99L);
        admin.setLogin("admin");
        admin.setName("Admin");
        admin.setAvatarUrl("https://example.com/admin.png");
        admin.setHtmlUrl("https://example.com/admin");
        admin.setType(User.Type.USER);

        IdentityProvider githubProvider = TestEntities.gitProvider(100L, IdentityProviderType.GITHUB);
        when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com"))
                .thenReturn(Optional.of(githubProvider));

        when(workspaceRepository.count()).thenReturn(0L);
        when(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(1L, admin.getId()))
                .thenReturn(Optional.empty());
        when(workspaceService.createWorkspace(anyString(), anyString(), anyString(), any(AccountType.class), anyLong()))
                .thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // User lookup for PAT bootstrap — scoped by provider id post-#1198
        when(userRepository.findByLoginAndProviderId("aet-org", 100L)).thenReturn(Optional.of(owner));
        when(userRepository.findByLogin("admin")).thenReturn(Optional.of(admin));

        provisioningService.bootstrapDefaultPatWorkspace();

        verify(workspaceMembershipService)
                .createMembership(workspace, admin.getId(), WorkspaceMembership.WorkspaceRole.ADMIN);
        // Default admin handling should not throw and should not trigger redundant
        // workspace creations
        verify(workspaceService).createWorkspace(anyString(), anyString(), anyString(), any(), anyLong());

        verify(connectionService)
                .provisionPatConnection(
                        eq(workspace),
                        eq(IntegrationKind.GITHUB),
                        eq("pat"),
                        any(ConnectionConfig.GitHubPatConfig.class),
                        eq("pat-token"),
                        anyString());
        assertThat(workspace.getId()).isEqualTo(1L);
    }

    @Test
    void bootstrapDefaultPatWorkspace_noAdminPresent_doesNotInvokeMembershipCreation() {
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("aet-org");
        workspace.setDisplayName("AET Org");
        workspace.setId(1L);

        User owner = new User();
        owner.setId(11L);
        owner.setLogin("aet-org");
        owner.setName("Owner");
        owner.setAvatarUrl("https://example.com/avatar.png");
        owner.setHtmlUrl("https://example.com");
        owner.setType(User.Type.USER);

        IdentityProvider githubProvider = TestEntities.gitProvider(100L, IdentityProviderType.GITHUB);
        when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com"))
                .thenReturn(Optional.of(githubProvider));

        when(workspaceRepository.count()).thenReturn(0L);
        when(workspaceService.createWorkspace(anyString(), anyString(), anyString(), any(AccountType.class), anyLong()))
                .thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByLoginAndProviderId("aet-org", 100L)).thenReturn(Optional.of(owner));
        when(userRepository.findByLogin("admin")).thenReturn(Optional.empty());

        provisioningService.bootstrapDefaultPatWorkspace();

        verify(workspaceMembershipService, never())
                .createMembership(any(Workspace.class), anyLong(), any(WorkspaceMembership.WorkspaceRole.class));
    }

    @Test
    void ensureAdminMembershipIsAddedWhenWorkspaceAlreadyExists() {
        Workspace workspace = new Workspace();
        workspace.setId(42L);
        workspace.setWorkspaceSlug("ls1intum");
        workspace.setDisplayName("ls1intum");

        User admin = new User();
        admin.setId(99L);
        admin.setLogin("admin");
        admin.setName("Admin");
        admin.setAvatarUrl("https://example.com/admin.png");
        admin.setHtmlUrl("https://example.com/admin");
        admin.setType(User.Type.USER);

        when(workspaceRepository.count()).thenReturn(1L);
        when(workspaceRepository.findByWorkspaceSlug("aet-org")).thenReturn(Optional.empty());
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(userRepository.findByLogin("admin")).thenReturn(Optional.of(admin));
        when(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), admin.getId()))
                .thenReturn(Optional.empty());

        provisioningService.bootstrapDefaultPatWorkspace();

        verify(workspaceMembershipService)
                .createMembership(workspace, admin.getId(), WorkspaceMembership.WorkspaceRole.ADMIN);
        verify(workspaceService, never()).createWorkspace(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void gitLabBootstrapToleratesCaseDuplicateWhenActiveGitLabWorkspaceExists() {
        Workspace gitlabWorkspace = new Workspace();
        gitlabWorkspace.setId(10L);
        gitlabWorkspace.setAccountLogin("hephaestustest");
        Workspace githubDuplicate = new Workspace();
        githubDuplicate.setId(11L);
        githubDuplicate.setAccountLogin("HephaestusTest");

        workspaceProperties = new WorkspaceProperties(
                false,
                new WorkspaceProperties.DefaultProperties("", "", List.of()),
                true,
                new WorkspaceProperties.GitLabDefaultProperties("hephaestustest", "pat-token", "https://gitlab.lrz.de"),
                WorkspaceProperties.CreationPolicy.SELF_SERVICE);
        provisioningService = new WorkspaceProvisioningService(
                workspaceProperties,
                workspaceRepository,
                repositoryToMonitorRepository,
                workspaceService,
                userRepository,
                gitProviderRepository,
                workspaceMembershipRepository,
                workspaceMembershipService,
                authenticatedGitProviderUserService,
                connectionService,
                List.of(),
                new ScmServerEndpointPolicy(new MockEnvironment()));
        when(workspaceRepository.findAllByAccountLoginIgnoreCase("hephaestustest"))
                .thenReturn(List.of(gitlabWorkspace, githubDuplicate));
        when(connectionService.findActive(10L, IntegrationKind.GITLAB)).thenReturn(Optional.of(mock(Connection.class)));

        provisioningService.bootstrapDefaultGitLabPatWorkspace();

        verify(workspaceService, never()).createWorkspace(anyString(), anyString(), anyString(), any(), anyLong());
    }
}
