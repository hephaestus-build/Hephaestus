package de.tum.cit.aet.hephaestus.workspace.spi;

/** Changes to repository monitors. Every command writes at once and joins the caller's transaction. */
public interface RepositoryMonitorCommands {
    /** Joins the caller's transaction. */
    void rename(long workspaceId, long monitorId, String nameWithOwner);
}
