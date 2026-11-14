package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;

/** Changes to repository monitors. Every command writes at once and joins the caller's transaction. */
public interface RepositoryMonitorCommands {
    /**
     * Adds a monitor and writes it at once, so a path or id that another monitor of the workspace holds fails here.
     * Joins the caller's transaction.
     *
     * @throws de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException if the workspace does not exist
     */
    RepositoryToMonitor add(long workspaceId, String nameWithOwner, long nativeId);

    /** Joins the caller's transaction. */
    void rename(long workspaceId, long monitorId, String nameWithOwner);
}
