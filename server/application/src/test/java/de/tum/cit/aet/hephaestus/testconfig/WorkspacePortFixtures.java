package de.tum.cit.aet.hephaestus.testconfig;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceQueryService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceCommandsAdapter;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceMembershipsAdapter;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceQueriesAdapter;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.time.Clock;
import java.util.Optional;

/** Existing workspace fixtures pass through the real record and command boundary. */
public final class WorkspacePortFixtures {
    private WorkspacePortFixtures() {}

    public static WorkspaceQueries queries(WorkspaceRepository repository) {
        return new WorkspaceQueriesAdapter(
                repository,
                mock(RepositoryToMonitorRepository.class),
                mock(ReviewScopeRepositoryRepository.class),
                mock(WorkspaceQueryService.class));
    }

    public static WorkspaceMemberships memberships(MembershipRepository memberships) {
        return new WorkspaceMembershipsAdapter(
                memberships,
                mock(WorkspaceRepository.class),
                mock(MembershipService.class),
                Clock.systemUTC(),
                mock(ConfigAuditPort.class));
    }

    public static WorkspaceCommands commands(WorkspaceRepository repository) {
        return new WorkspaceCommandsAdapter(
                repository, mock(RepositoryToMonitorRepository.class), mock(ReviewScopeRepositoryRepository.class));
    }

    public static WorkspaceQueries.ReviewWorkspace reviewWorkspace(Workspace workspace) {
        var repository = mock(WorkspaceRepository.class);
        when(repository.findById(workspace.getId())).thenReturn(Optional.of(workspace));
        return queries(repository).findById(workspace.getId()).orElseThrow();
    }
}
