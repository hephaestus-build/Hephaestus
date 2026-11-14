package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Optional;

/**
 * The tenant root, locked until the caller's transaction ends. Every method joins that transaction. A returned workspace is
 * for reading and for handing on; only {@link WorkspaceRoots} changes it.
 */
public interface WorkspaceLocks {
    /** The workspace, locked. */
    Optional<Workspace> lock(long workspaceId);

    /** The workspace that holds or last held the GitHub App installation, locked. */
    Optional<Workspace> lockByInstallation(long installationId);

    /** The one workspace that binds the organization, locked. */
    Optional<Workspace> lockByOrganization(long organizationId);
}
