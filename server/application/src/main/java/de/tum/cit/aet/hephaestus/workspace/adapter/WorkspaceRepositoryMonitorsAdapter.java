package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorSources;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class WorkspaceRepositoryMonitorsAdapter
        implements WorkspaceRepositoryMonitors, RepositoryMonitorSources, RepositoryMonitorCommands {
    private final RepositoryToMonitorRepository monitors;
    private final WorkspaceRepository workspaces;

    @Override
    public List<RepositoryToMonitor> monitors(long workspaceId) {
        return monitors.findByWorkspaceId(workspaceId);
    }

    @Override
    public List<RepositoryToMonitor> monitorsOf(long workspaceId, long nativeId) {
        return monitors.findByWorkspaceIdAndNativeId(workspaceId, nativeId);
    }

    @Override
    public List<RepositoryToMonitor> monitorsOfRepository(long workspaceId, long repositoryId) {
        return monitors.findByWorkspaceIdAndSourceRepositoryId(workspaceId, repositoryId);
    }

    @Override
    public boolean monitorsPath(long workspaceId, String nameWithOwner) {
        return monitors.existsByWorkspaceIdAndNameWithOwner(workspaceId, nameWithOwner);
    }

    @Override
    public Optional<RepositoryToMonitor> find(long monitorId) {
        return monitors.findById(monitorId);
    }

    @Override
    public List<Long> sourceRepositoryIds(long monitorId) {
        return monitors.findSourceRepositoryIdsOfMonitor(monitorId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RepositoryToMonitor add(long workspaceId, String nameWithOwner, long nativeId) {
        var monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(nameWithOwner);
        monitor.setNativeId(nativeId);
        monitor.setWorkspace(workspaces
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId)));
        return monitors.saveAndFlush(monitor);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void rename(long workspaceId, long monitorId, String nameWithOwner) {
        var monitor = monitors.findByWorkspaceId(workspaceId).stream()
                .filter(found -> found.getId() == monitorId)
                .findFirst()
                .orElseThrow();
        monitor.setNameWithOwner(nameWithOwner);
        monitors.save(monitor);
    }
}
