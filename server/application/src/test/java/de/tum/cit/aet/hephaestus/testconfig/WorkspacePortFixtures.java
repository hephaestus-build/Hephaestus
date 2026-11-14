package de.tum.cit.aet.hephaestus.testconfig;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceCommandsAdapter;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceQueriesAdapter;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.util.Optional;

/** Existing workspace fixtures pass through the real record and command boundary. */
public final class WorkspacePortFixtures {
    private WorkspacePortFixtures() {}

    public static WorkspaceQueries queries(WorkspaceRepository repository) {
        return new WorkspaceQueriesAdapter(
                repository, mock(RepositoryToMonitorRepository.class), mock(ReviewScopeRepositoryRepository.class));
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
