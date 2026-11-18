package de.tum.cit.aet.hephaestus.workspace.spi;

/** Who in a workspace sees practice results per person. */
public interface WorkspaceResultVisibility {
    /** Whether the workspace's admins see practice results per person; false for an unknown workspace. */
    boolean perPersonResultsForAdmins(long workspaceId);
}
