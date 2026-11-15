package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorQuery;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepositoryMonitorQueryAdapter implements RepositoryMonitorQuery {
    private final RepositoryToMonitorRepository monitors;

    @Override
    @WorkspaceAgnostic(
            "Instance-wide repository exclusion finds monitor identities; withdrawal locks and rechecks each workspace")
    public List<MonitorRef> findByNameIgnoringCase(String nameWithOwner) {
        return monitors.findAllWithWorkspaceByNameIgnoringCase(nameWithOwner).stream()
                .map(monitor -> new MonitorRef(
                        Objects.requireNonNull(monitor.getWorkspace()).getId(), monitor.getId()))
                .toList();
    }
}
