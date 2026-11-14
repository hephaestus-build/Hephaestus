package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import java.util.List;

/**
 * The repositories that a workspace monitors. The SCM adapters read a monitor's sync state from the returned rows;
 * only these commands change a monitor's identity.
 */
public interface WorkspaceRepositoryMonitors {
    List<RepositoryToMonitor> monitors(long workspaceId);
}
