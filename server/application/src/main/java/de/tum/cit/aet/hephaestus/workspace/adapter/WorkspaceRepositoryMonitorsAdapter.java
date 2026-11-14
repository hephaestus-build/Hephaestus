package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class WorkspaceRepositoryMonitorsAdapter implements WorkspaceRepositoryMonitors, RepositoryMonitorCommands {
    private final RepositoryToMonitorRepository monitors;

    @Override
    public List<RepositoryToMonitor> monitors(long workspaceId) {
        return monitors.findByWorkspaceId(workspaceId);
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
