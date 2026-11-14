package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import java.util.List;
import java.util.Optional;

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

    /** Whether a monitor other than {@code exceptMonitorId} holds the path. */
    boolean monitorsPathElsewhere(long workspaceId, String nameWithOwner, long exceptMonitorId);

    /** The workspace's one monitor of the repository with this provider-stable id. */
    Optional<RepositoryToMonitor> monitorOf(long workspaceId, long nativeId);

    Optional<RepositoryToMonitor> monitorAt(long workspaceId, String nameWithOwner);

    /** The mirrored repositories that the workspace's monitors read. */
    List<Long> workspaceSourceRepositoryIds(long workspaceId);
}
