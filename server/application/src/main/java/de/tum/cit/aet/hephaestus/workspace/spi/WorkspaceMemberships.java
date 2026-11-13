package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.util.Optional;

public interface WorkspaceMemberships {
    Optional<WorkspaceRole> activeRole(long workspaceId, long accountId);
}
