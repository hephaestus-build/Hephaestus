package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;

/**
 * Puts a test workspace into a status through its transitions. Only a test marks a workspace purged without the purge:
 * production purges through {@link WorkspaceLifecycleService}, which first erases the workspace's data.
 */
public final class WorkspaceStatusFixture {

    private WorkspaceStatusFixture() {}

    public static Workspace markPurged(Workspace workspace) {
        workspace.markPurged();
        return workspace;
    }

    public static Workspace withStatus(Workspace workspace, WorkspaceStatus status) {
        switch (status) {
            case ACTIVE -> workspace.resume();
            case SUSPENDED -> workspace.suspend();
            case PURGED -> workspace.markPurged();
        }
        return workspace;
    }
}
