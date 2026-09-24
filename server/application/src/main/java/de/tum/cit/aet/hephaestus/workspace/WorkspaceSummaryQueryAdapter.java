package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkspaceSummaryQueryAdapter implements WorkspaceSummaryQuery {

    private final WorkspaceRepository workspaceRepository;

    WorkspaceSummaryQueryAdapter(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspaceSummary> findById(long workspaceId) {
        return workspaceRepository.findById(workspaceId).map(WorkspaceSummaryQueryAdapter::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, WorkspaceSummary> findAllByIds(Collection<Long> workspaceIds) {
        if (workspaceIds.isEmpty()) return Map.of();
        return workspaceRepository.findAllById(workspaceIds).stream()
                .map(WorkspaceSummaryQueryAdapter::summary)
                .collect(Collectors.toMap(WorkspaceSummary::id, summary -> summary));
    }

    private static WorkspaceSummary summary(Workspace workspace) {
        return new WorkspaceSummary(workspace.getId(), workspace.getWorkspaceSlug(), workspace.getDisplayName());
    }
}
