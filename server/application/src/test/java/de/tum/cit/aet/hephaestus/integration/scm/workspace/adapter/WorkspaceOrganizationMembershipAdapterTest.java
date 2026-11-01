package de.tum.cit.aet.hephaestus.integration.scm.workspace.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.OrganizationSyncedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

class WorkspaceOrganizationMembershipAdapterTest extends BaseUnitTest {

    private static final String GROUP = "course/intro";
    private static final long ORGANIZATION_ID = 5L;
    private static final long PROVIDER_ID = 10L;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private MembershipService membershipService;

    @Mock
    private OrganizationMembershipRepository roster;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private TeamMembershipRepository teamMembershipRepository;

    @Mock
    private WorkspaceActorSelector actorSelector;

    @Mock
    private ConnectionRepository connections;

    private WorkspaceOrganizationMembershipAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WorkspaceOrganizationMembershipAdapter(
                workspaceRepository,
                membershipService,
                roster,
                organizationRepository,
                teamMembershipRepository,
                actorSelector,
                connections,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        // The persistence context the container injects; refreshing a workspace the test built changes nothing.
        ReflectionTestUtils.setField(adapter, "entityManager", mock(EntityManager.class));
        Organization group = new Organization();
        group.setId(ORGANIZATION_ID);
        group.setLogin(GROUP);
        group.setProvider(TestEntities.gitProvider(PROVIDER_ID, IdentityProviderType.GITLAB));
        lenient()
                .when(workspaceRepository.findByOrganizationId(ORGANIZATION_ID))
                .thenReturn(Optional.empty());
        when(organizationRepository.findById(ORGANIZATION_ID)).thenReturn(Optional.of(group));
    }

    @Test
    void shouldReachTheOneUnlinkedWorkspaceOnTheGroupsInstanceAmongSamePathWorkspaces() {
        Workspace onOtherInstance = unlinked(1L, PROVIDER_ID + 1);
        Workspace onThisInstance = unlinked(2L, PROVIDER_ID);
        when(workspaceRepository.findAllByAccountLoginIgnoreCase(GROUP))
                .thenReturn(List.of(onOtherInstance, onThisInstance));
        connectedWithSelection(2L, RepositorySelection.ALL);

        adapter.onOrganizationMembershipsSynced(new OrganizationSyncedEvent(ORGANIZATION_ID, GROUP, true));

        verify(membershipService).syncWorkspaceMembers(eq(onThisInstance), any());
        verify(membershipService, never()).syncWorkspaceMembers(eq(onOtherInstance), any());
    }

    @Test
    void shouldReachNoWorkspaceWhenTwoUnlinkedWorkspacesOnTheInstanceShareThePath() {
        Workspace first = unlinked(1L, PROVIDER_ID);
        Workspace second = unlinked(2L, PROVIDER_ID);
        when(workspaceRepository.findAllByAccountLoginIgnoreCase(GROUP)).thenReturn(List.of(first, second));

        adapter.onOrganizationMembershipsSynced(new OrganizationSyncedEvent(ORGANIZATION_ID, GROUP, true));

        verify(membershipService, never()).syncWorkspaceMembers(any(), any());
    }

    @Test
    void shouldReachTheLinkedWorkspaceOnlyWhileItIsConnectedToTheGroupsInstance() {
        Workspace linked = linked(3L);
        OrganizationSyncedEvent queued = new OrganizationSyncedEvent(ORGANIZATION_ID, GROUP, true);

        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.empty());
        adapter.onOrganizationMembershipsSynced(queued);
        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.of(PROVIDER_ID + 1));
        adapter.onOrganizationMembershipsSynced(queued);
        verify(membershipService, never()).syncWorkspaceMembers(any(), any());

        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.of(PROVIDER_ID));
        connectedWithSelection(3L, RepositorySelection.ALL);
        adapter.onOrganizationMembershipsSynced(queued);
        verify(membershipService).syncWorkspaceMembers(eq(linked), any());
    }

    @Test
    void shouldGrantNothingFromAGitLabGroupWhoseConnectionIsGoneOrWhichSelectedItsProjects() {
        linked(3L);
        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.of(PROVIDER_ID));
        OrganizationSyncedEvent queued = new OrganizationSyncedEvent(ORGANIZATION_ID, GROUP, true);

        // No active GitLab connection once the lifecycle lock is held: disconnected or replaced while waiting.
        adapter.onOrganizationMembershipsSynced(queued);
        connectedWithSelection(3L, RepositorySelection.SELECTED);
        adapter.onOrganizationMembershipsSynced(queued);

        verify(membershipService, never()).syncWorkspaceMembers(any(), any());
    }

    @Test
    void shouldGrantGroupMembersOnlyFromTheConnectedInstanceAndRootAndLeaveOtherProvidersAlone() {
        Workspace linked = new Workspace();
        linked.setId(3L);
        linked.setOrganizationId(ORGANIZATION_ID);
        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.of(PROVIDER_ID));
        connectedWithSelection(3L, RepositorySelection.ALL);

        assertThat(adapter.mayApplyGroupGrants(linked, IdentityProviderType.GITLAB, PROVIDER_ID + 1, GROUP, null))
                .as("teams of another instance")
                .isFalse();
        assertThat(adapter.mayApplyGroupGrants(linked, IdentityProviderType.GITLAB, PROVIDER_ID, "course/other", null))
                .as("teams of another root group")
                .isFalse();
        assertThat(adapter.mayApplyGroupGrants(linked, IdentityProviderType.GITLAB, PROVIDER_ID, GROUP, null))
                .isTrue();
        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.of(PROVIDER_ID + 1));
        assertThat(adapter.mayApplyGroupGrants(linked, IdentityProviderType.GITLAB, PROVIDER_ID, GROUP, null))
                .as("a workspace now connected to another instance")
                .isFalse();

        Workspace onGitHub = new Workspace();
        onGitHub.setId(4L);
        assertThat(adapter.mayApplyGroupGrants(onGitHub, IdentityProviderType.GITHUB, PROVIDER_ID, "octo-org", null))
                .isTrue();
        verifyNoInteractions(membershipService);
    }

    private Workspace linked(long id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setOrganizationId(ORGANIZATION_ID);
        when(workspaceRepository.findByOrganizationId(ORGANIZATION_ID)).thenReturn(Optional.of(workspace));
        return workspace;
    }

    private Workspace unlinked(long id, long connectedProviderId) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setAccountLogin(GROUP);
        when(actorSelector.connectedProviderId(id)).thenReturn(Optional.of(connectedProviderId));
        return workspace;
    }

    /** The workspace's GitLab connection is active once its lifecycle lock is held, and it stores this selection. */
    private void connectedWithSelection(long workspaceId, RepositorySelection selection) {
        when(connections.lockActiveConfig(workspaceId, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(
                        new ConnectionConfig.GitLabConfig("https://gitlab.lrz.de", null, null, Set.of(), null)));
        WorkspaceRepository.MonitorPolicy policy = mock(WorkspaceRepository.MonitorPolicy.class);
        lenient().when(policy.getRepositorySelection()).thenReturn(selection);
        when(workspaceRepository.findMonitorPolicy(workspaceId)).thenReturn(Optional.of(policy));
    }
}
