package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.TeamMembershipListener.TeamsSyncedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** A team sync event changes a workspace only when its teams are that workspace's group on its connected instance. */
class WorkspaceTeamMembershipAdapterTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long PROVIDER_ID = 10L;
    private static final String ROOT = "course/intro";

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMembershipService membershipService;

    @Mock
    private TeamMembershipRepository teamMembershipRepository;

    @Mock
    private WorkspaceOrganizationMembershipAdapter organizationAdapter;

    @Mock
    private WorkspaceActorSelector actorSelector;

    private WorkspaceTeamMembershipAdapter adapter;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        adapter = new WorkspaceTeamMembershipAdapter(
                workspaceRepository, membershipService, teamMembershipRepository, organizationAdapter, actorSelector);
        Organization group = new Organization();
        group.setId(5L);
        group.setLogin(ROOT);
        group.setProvider(TestEntities.gitProvider(PROVIDER_ID, IdentityProviderType.GITLAB));
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setOrganization(group);
        workspace.setMembersSyncedAt(Instant.now());
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        lenient().when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.of(PROVIDER_ID));
        lenient()
                .when(teamMembershipRepository.findDistinctUserIdsOfSubteams(any(), anyLong()))
                .thenReturn(Set.of(7L));
    }

    @Test
    void shouldAddNobodyFromTeamsOfAnotherInstanceOrAnotherRoot() {
        adapter.onTeamMembershipsSynced(new TeamsSyncedEvent(WORKSPACE_ID, ROOT, PROVIDER_ID + 1, true));
        adapter.onTeamMembershipsSynced(new TeamsSyncedEvent(WORKSPACE_ID, ROOT, PROVIDER_ID + 1, false));
        adapter.onTeamMembershipsSynced(new TeamsSyncedEvent(WORKSPACE_ID, "course/other", PROVIDER_ID, false));

        verify(membershipService, never()).ensureMemberships(any(), any());
        verify(organizationAdapter, never()).reconcileWorkspaceMembers(any(), anyLong(), anyBoolean());
    }

    @Test
    void shouldAddNobodyOnceTheWorkspaceIsConnectedToAnotherInstance() {
        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.of(PROVIDER_ID + 1));

        adapter.onTeamMembershipsSynced(new TeamsSyncedEvent(WORKSPACE_ID, ROOT, PROVIDER_ID, true));

        verify(membershipService, never()).ensureMemberships(any(), any());
        verify(organizationAdapter, never()).reconcileWorkspaceMembers(any(), anyLong(), anyBoolean());
    }

    @Test
    void shouldOnlyAddMembersFromAPartialPassOfItsOwnTeams() {
        adapter.onTeamMembershipsSynced(new TeamsSyncedEvent(WORKSPACE_ID, ROOT, PROVIDER_ID, false));

        verify(membershipService).ensureMemberships(workspace, Set.of(7L));
        verify(organizationAdapter, never()).reconcileWorkspaceMembers(any(), anyLong(), anyBoolean());
    }

    @Test
    void shouldReconcileTheWorkspaceAfterACompletePassOfItsOwnTeams() {
        adapter.onTeamMembershipsSynced(new TeamsSyncedEvent(WORKSPACE_ID, ROOT, PROVIDER_ID, true));

        verify(organizationAdapter).reconcileWorkspaceMembers(workspace, 5L, true);
        verify(membershipService, never()).ensureMemberships(any(), any());
    }
}
