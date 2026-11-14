package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import java.util.List;

/**
 * The repositories that a workspace monitors. The SCM adapters read a monitor's sync state from the returned rows;
 * only these commands change a monitor's identity.
 */
public interface WorkspaceRepositoryMonitors {
    List<RepositoryToMonitor> monitors(long workspaceId);

    /** The workspace's monitors of the repository with this provider-stable id. */
    List<RepositoryToMonitor> monitorsOf(long workspaceId, long nativeId);

    /** The workspace's monitors of a mirrored repository. */
    List<RepositoryToMonitor> monitorsOfRepository(long workspaceId, long repositoryId);

    boolean monitorsPath(long workspaceId, String nameWithOwner);
}
