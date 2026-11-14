package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.ScmMirrorErasedEvent;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceQueryService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceQueriesAdapter;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceRepositoryMonitorsAdapter;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceRootsAdapter;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

class ScmWorkspaceContentEraserTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private WorkspaceRepositoryMonitorService repositoryMonitorService;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private OrganizationMembershipRepository organizationMembershipRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private WorkspaceOrganizationLookup organizations;

    private ScmWorkspaceContentEraser eraser;

    @BeforeEach
    void setUp() {
        var roots = new WorkspaceRootsAdapter(workspaceRepository, mock(MembershipService.class));
        var monitors = new WorkspaceRepositoryMonitorsAdapter(repositoryToMonitorRepository, workspaceRepository);
        eraser = new ScmWorkspaceContentEraser(
                roots,
                roots,
                new WorkspaceQueriesAdapter(
                        workspaceRepository,
                        repositoryToMonitorRepository,
                        mock(ReviewScopeRepositoryRepository.class),
                        mock(WorkspaceQueryService.class)),
                organizations,
                monitors,
                monitors,
                repositoryRepository,
                repositoryMonitorService,
                teamRepository,
                organizationMembershipRepository,
                eventPublisher);
    }

    private Workspace workspaceWithMonitors(String... namesWithOwner) {
        Workspace workspace = newWorkspace();
        List<RepositoryToMonitor> monitors = List.of(namesWithOwner).stream()
                .map(name -> {
                    RepositoryToMonitor monitor = new RepositoryToMonitor();
                    monitor.setNativeId(1L);
                    monitor.setNameWithOwner(name);
                    monitor.setWorkspace(workspace);
                    return monitor;
                })
                .toList();
        workspace.getRepositoriesToMonitor().addAll(monitors);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        lenient().when(workspaceRepository.findByIdForUpdate(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(repositoryToMonitorRepository.findSourceRepositoryIds(WORKSPACE_ID))
                .thenReturn(LongStream.range(101L, 101L + namesWithOwner.length)
                        .boxed()
                        .toList());
        return workspace;
    }

    @Test
    void erase_delegatesTheCascadeToTheOrphanGuardedDeleteForEveryMonitoredRepository() {
        workspaceWithMonitors("acme/alpha", "acme/beta");

        eraser.eraseWorkspaceScmMirror(WORKSPACE_ID);

        verify(repositoryMonitorService).deleteRepositoryIfOrphaned(101L);
        verify(repositoryMonitorService).deleteRepositoryIfOrphaned(102L);
    }

    @Test
    void erase_clearsThisWorkspacesMonitorsAndFlushesBeforeTheOrphanCheckReadsThem() {
        Workspace workspace = workspaceWithMonitors("acme/alpha");

        eraser.eraseWorkspaceScmMirror(WORKSPACE_ID);

        assertThat(workspace.getRepositoriesToMonitor()).isEmpty();
        // saveAndFlush, not save: a pending orphanRemoval DELETE is invisible to
        // countForRepository.
        InOrder order = inOrder(workspaceRepository, repositoryMonitorService);
        order.verify(workspaceRepository).saveAndFlush(workspace);
        order.verify(repositoryMonitorService).deleteRepositoryIfOrphaned(101L);
    }

    @Test
    void erase_publishesTheDerivedRowEventBeforeDroppingTheItemsItPointsAt() {
        workspaceWithMonitors("acme/alpha");

        eraser.eraseWorkspaceScmMirror(WORKSPACE_ID);

        InOrder order = inOrder(eventPublisher, repositoryMonitorService);
        order.verify(eventPublisher).publishEvent(new ScmMirrorErasedEvent(WORKSPACE_ID));
        order.verify(eventPublisher).publishEvent(new RepositoryMonitoringChangedEvent(WORKSPACE_ID, List.of(101L)));
        order.verify(repositoryMonitorService).deleteRepositoryIfOrphaned(101L);
    }

    @Test
    void erase_releasesTheRepositoryItemsItKeptForStoppedProjectsBeforeTheOrphanCheck() {
        workspaceWithMonitors("acme/alpha");
        when(repositoryRepository.findIndexedRepositoryIds(WORKSPACE_ID)).thenReturn(List.of(101L, 300L));

        eraser.eraseWorkspaceScmMirror(WORKSPACE_ID);

        InOrder order = inOrder(eventPublisher, repositoryMonitorService);
        order.verify(eventPublisher)
                .publishEvent(new RepositoryMonitoringChangedEvent(WORKSPACE_ID, List.of(101L, 300L)));
        order.verify(repositoryMonitorService).deleteRepositoryIfOrphaned(101L);
        order.verify(repositoryMonitorService).deleteRepositoryIfOrphaned(300L);
    }

    @Test
    void erase_onMissingWorkspace_isASilentNoOp() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        eraser.eraseWorkspaceScmMirror(WORKSPACE_ID);

        verifyNoInteractions(eventPublisher, repositoryMonitorService, teamRepository);
    }

    @Test
    void erase_dropsOrgTierMirrorOnlyWhenThisIsTheLastWorkspaceForTheOrganization() {
        Workspace workspace = workspaceWithMonitors("acme/alpha");
        workspace.setOrganizationId(7L);
        when(organizations.find(workspace)).thenReturn(Optional.of(organization(7L, "acme")));
        when(workspaceRepository.countOtherActiveWorkspacesForOrganization(7L, WORKSPACE_ID))
                .thenReturn(0L);
        Team team = new Team();
        when(teamRepository.findByOrganizationIgnoreCaseAndProviderId("acme", 3L))
                .thenReturn(List.of(team));

        eraser.eraseWorkspaceScmMirror(WORKSPACE_ID);

        verify(teamRepository).deleteAll(List.of(team));
        verify(organizationMembershipRepository).deleteByOrganizationId(7L);
    }

    @Test
    void erase_releasesTheOrganizationLinkAndFlushesItBeforeTheLastTenantGuardCounts() {
        Workspace workspace = workspaceWithMonitors("acme/alpha");
        workspace.setOrganizationId(7L);
        when(organizations.find(workspace)).thenReturn(Optional.of(organization(7L, "acme")));
        when(workspaceRepository.countOtherActiveWorkspacesForOrganization(7L, WORKSPACE_ID))
                .thenReturn(0L);

        eraser.eraseWorkspaceScmMirror(WORKSPACE_ID);

        // A retained organization link would occupy its unique binding slot after disconnect.
        assertThat(workspace.getOrganizationId()).isNull();
        // Flushed first, or the guard's COUNT still reads the stale link.
        InOrder order = inOrder(workspaceRepository);
        order.verify(workspaceRepository, times(2)).saveAndFlush(workspace);
        order.verify(workspaceRepository).countOtherActiveWorkspacesForOrganization(7L, WORKSPACE_ID);
    }

    private static Workspace newWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        return workspace;
    }

    private static Organization organization(long id, String login) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setLogin(login);
        IdentityProvider provider = new IdentityProvider();
        provider.setId(3L);
        organization.setProvider(provider);
        return organization;
    }
}
