package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.OrganizationSyncedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** A roster reaches the workspace linked to its organization row, or the one unlinked workspace on its instance. */
class WorkspaceOrganizationMembershipAdapterTest extends BaseUnitTest {

    private static final String GROUP = "course/intro";
    private static final long ORGANIZATION_ID = 5L;
    private static final long PROVIDER_ID = 10L;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMembershipRepository workspaceMembershipRepository;

    @Mock
    private WorkspaceMembershipService membershipService;

    @Mock
    private OrganizationMembershipRepository roster;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private TeamMembershipRepository teamMembershipRepository;

    @Mock
    private WorkspaceActorSelector actorSelector;

    private WorkspaceOrganizationMembershipAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WorkspaceOrganizationMembershipAdapter(
                workspaceRepository,
                workspaceMembershipRepository,
                membershipService,
                roster,
                organizationRepository,
                teamMembershipRepository,
                actorSelector);
        Organization group = new Organization();
        group.setId(ORGANIZATION_ID);
        group.setLogin(GROUP);
        group.setProvider(TestEntities.gitProvider(PROVIDER_ID, IdentityProviderType.GITLAB));
        lenient()
                .when(workspaceRepository.findByOrganization_Id(ORGANIZATION_ID))
                .thenReturn(Optional.empty());
        when(organizationRepository.findById(ORGANIZATION_ID)).thenReturn(Optional.of(group));
    }

    @Test
    void shouldReachTheOneUnlinkedWorkspaceOnTheGroupsInstanceAmongSamePathWorkspaces() {
        Workspace onOtherInstance = unlinked(1L, PROVIDER_ID + 1);
        Workspace onThisInstance = unlinked(2L, PROVIDER_ID);
        when(workspaceRepository.findAllByAccountLoginIgnoreCase(GROUP))
                .thenReturn(List.of(onOtherInstance, onThisInstance));

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
        Workspace linked = new Workspace();
        linked.setId(3L);
        when(workspaceRepository.findByOrganization_Id(ORGANIZATION_ID)).thenReturn(Optional.of(linked));
        OrganizationSyncedEvent queued = new OrganizationSyncedEvent(ORGANIZATION_ID, GROUP, true);

        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.empty());
        adapter.onOrganizationMembershipsSynced(queued);
        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.of(PROVIDER_ID + 1));
        adapter.onOrganizationMembershipsSynced(queued);
        verify(membershipService, never()).syncWorkspaceMembers(any(), any());

        when(actorSelector.connectedProviderId(3L)).thenReturn(Optional.of(PROVIDER_ID));
        adapter.onOrganizationMembershipsSynced(queued);
        verify(membershipService).syncWorkspaceMembers(eq(linked), any());
    }

    private Workspace unlinked(long id, long connectedProviderId) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setAccountLogin(GROUP);
        when(actorSelector.connectedProviderId(id)).thenReturn(Optional.of(connectedProviderId));
        return workspace;
    }
}
