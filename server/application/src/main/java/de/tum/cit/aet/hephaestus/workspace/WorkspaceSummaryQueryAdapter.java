package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    public boolean exists(long workspaceId) {
        return workspaceRepository.existsById(workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, WorkspaceSummary> findAllByIds(Collection<Long> workspaceIds) {
        if (workspaceIds.isEmpty()) return Map.of();
        return workspaceRepository.findAllById(workspaceIds).stream()
                .map(WorkspaceSummaryQueryAdapter::summary)
                .collect(Collectors.toMap(WorkspaceSummary::id, summary -> summary));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findActiveIdBySlug(String slug) {
        return workspaceRepository.findActiveIdBySlug(slug).map(WorkspaceIdData::id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspaceState> findState(long workspaceId) {
        return workspaceRepository.findById(workspaceId).map(WorkspaceSummaryQueryAdapter::state);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<WorkspaceState> findStateForUpdate(long workspaceId) {
        return workspaceRepository.findByIdForUpdate(workspaceId).map(WorkspaceSummaryQueryAdapter::state);
    }

    private static WorkspaceState state(Workspace workspace) {
        return new WorkspaceState(
                workspace.getId(),
                workspace.getWorkspaceSlug(),
                workspace.getStatus().name(),
                workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE);
    }

    private static WorkspaceSummary summary(Workspace workspace) {
        return new WorkspaceSummary(workspace.getId(), workspace.getWorkspaceSlug(), workspace.getDisplayName());
    }
}
