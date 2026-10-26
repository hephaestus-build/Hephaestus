package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceProperties;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugHistoryRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugReservationRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugService;
import java.util.List;
import java.util.Optional;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

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
    private MembershipRepository workspaceMembershipRepository;

    @Mock
    private MembershipService workspaceMembershipService;

    @Mock
    private ConnectionService connectionService;

    private WorkspaceProvisioningService provisioningService;
    private WorkspaceSlugService workspaceSlugs;

    private WorkspaceProperties workspaceProperties;

    @BeforeEach
    void setUp() {
        workspaceSlugs = new WorkspaceSlugService(
                workspaceRepository,
                mock(WorkspaceSlugHistoryRepository.class),
                mock(WorkspaceSlugReservationRepository.class));
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
                workspaceSlugs,
                userRepository,
                gitProviderRepository,
                workspaceMembershipRepository,
                workspaceMembershipService,
                connectionService,
                List.of(),
                new ScmServerEndpointPolicy(new MockEnvironment(), WebClient.builder()),
                WebClient.builder());
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
        when(workspaceMembershipService.accountIdForUser(admin.getId())).thenReturn(199L);
        when(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(1L, 199L))
                .thenReturn(Optional.empty());
        when(workspaceService.createWorkspace(anyString(), anyString(), anyString(), any(AccountType.class), anyLong()))
                .thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByLoginAndProviderId("aet-org", 100L)).thenReturn(Optional.of(owner));
        when(userRepository.findByLogin("admin")).thenReturn(Optional.of(admin));

        provisioningService.bootstrapDefaultPatWorkspace();

        verify(workspaceMembershipService).createMembership(workspace, 199L, Membership.WorkspaceRole.ADMIN);
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
                .createMembership(any(Workspace.class), anyLong(), any(Membership.WorkspaceRole.class));
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
        when(workspaceMembershipService.accountIdForUser(admin.getId())).thenReturn(199L);
        when(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(workspace.getId(), 199L))
                .thenReturn(Optional.empty());

        provisioningService.bootstrapDefaultPatWorkspace();

        verify(workspaceMembershipService).createMembership(workspace, 199L, Membership.WorkspaceRole.ADMIN);
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
                workspaceSlugs,
                userRepository,
                gitProviderRepository,
                workspaceMembershipRepository,
                workspaceMembershipService,
                connectionService,
                List.of(),
                new ScmServerEndpointPolicy(new MockEnvironment(), WebClient.builder()),
                WebClient.builder());
        when(workspaceRepository.findAllByAccountLoginIgnoreCase("hephaestustest"))
                .thenReturn(List.of(gitlabWorkspace, githubDuplicate));
        when(connectionService.findActive(10L, IntegrationKind.GITLAB)).thenReturn(Optional.of(mock(Connection.class)));

        provisioningService.bootstrapDefaultGitLabPatWorkspace();

        verify(workspaceService, never()).createWorkspace(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "unstated",
            value = {"true, BOT", "false, USER", "unstated, unstated"})
    void shouldRecordAGitLabTokensOwnerAsTheBotOrPersonGitLabSaysTheyAre(@Nullable Boolean bot, @Nullable String type)
            throws Exception {
        try (MockWebServer gitlab = new MockWebServer()) {
            gitlab.start();
            String origin = "http://127.0.0.1:" + gitlab.getPort();
            MockEnvironment environment = new MockEnvironment().withProperty("hephaestus.e2e.scm-origin", origin);
            environment.setActiveProfiles("e2e");
            WorkspaceProvisioningService service = new WorkspaceProvisioningService(
                    workspaceProperties,
                    workspaceRepository,
                    repositoryToMonitorRepository,
                    workspaceService,
                    workspaceSlugs,
                    userRepository,
                    gitProviderRepository,
                    workspaceMembershipRepository,
                    workspaceMembershipService,
                    connectionService,
                    List.of(),
                    new ScmServerEndpointPolicy(environment, WebClient.builder()),
                    WebClient.builder());
            gitlab.enqueue(new MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("{\"id\":90001,\"username\":\"ab12cde\",\"name\":\"Student\""
                            + (bot == null ? "" : ",\"bot\":" + bot)
                            + "}")
                    .build());
            when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, origin))
                    .thenReturn(Optional.of(TestEntities.gitProvider(5L, IdentityProviderType.GITLAB)));
            when(userRepository.findByLoginAndProviderId("course", 5L)).thenReturn(Optional.empty());
            User owner = new User();
            owner.setId(7L);
            when(userRepository.findByLoginAndProviderId("ab12cde", 5L)).thenReturn(Optional.of(owner));

            assertThat(service.resolveOrCreateGitLabUser("pat", origin, "course"))
                    .isEqualTo(7L);

            verify(userRepository)
                    .upsertUser(
                            eq(90001L),
                            eq(5L),
                            eq("ab12cde"),
                            any(),
                            any(),
                            any(),
                            type == null ? isNull() : eq(type),
                            any(),
                            any(),
                            any());
        }
    }
}
