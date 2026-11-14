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
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class WorkspaceRepositoryMonitorsAdapter
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
    public boolean monitorsPathElsewhere(long workspaceId, String nameWithOwner, long exceptMonitorId) {
        return monitors.existsByWorkspaceIdAndNameWithOwnerAndIdNot(workspaceId, nameWithOwner, exceptMonitorId);
    }

    @Override
    public Optional<RepositoryToMonitor> monitorOf(long workspaceId, long nativeId) {
        return monitors.findOneByWorkspaceIdAndNativeId(workspaceId, nativeId);
    }

    @Override
    public Optional<RepositoryToMonitor> monitorAt(long workspaceId, String nameWithOwner) {
        return monitors.findByWorkspaceIdAndNameWithOwner(workspaceId, nameWithOwner);
    }

    @Override
    public long countForRepository(long repositoryId) {
        return monitors.countForRepository(repositoryId);
    }

    @Override
    public List<Long> workspaceSourceRepositoryIds(long workspaceId) {
        return monitors.findSourceRepositoryIds(workspaceId);
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
        var workspace = workspaces
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
        monitor.setWorkspace(workspace);
        var saved = monitors.saveAndFlush(monitor);
        // A monitor set that this transaction already loaded would miss the new row until it is read again.
        if (Hibernate.isInitialized(workspace.getRepositoriesToMonitor()))
            workspace.getRepositoriesToMonitor().add(saved);
        return saved;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void rename(long workspaceId, long monitorId, String nameWithOwner) {
        var monitor = monitors.findByWorkspaceId(workspaceId).stream()
                .filter(found -> found.getId() == monitorId)
                .findFirst()
                .orElseThrow();
        monitor.setNameWithOwner(nameWithOwner);
        monitors.saveAndFlush(monitor);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void remove(long workspaceId, long monitorId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        // The monitor set's orphan removal deletes the row.
        workspace.getRepositoriesToMonitor().removeIf(monitor -> monitor.getId() == monitorId);
        workspaces.saveAndFlush(workspace);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clear(long workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        workspace.getRepositoriesToMonitor().clear();
        workspaces.saveAndFlush(workspace);
    }
}
