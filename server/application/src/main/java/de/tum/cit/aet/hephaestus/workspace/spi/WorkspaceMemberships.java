package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.util.Optional;

public interface WorkspaceMemberships {
    Optional<WorkspaceRole> activeRole(long workspaceId, long accountId);

    long accountIdForUser(long scmUserId);

    /** Whether the account has a membership row in the workspace, also one it left. */
    boolean hasMembership(long workspaceId, long accountId);

    /** The caller holds its capture and account-write fences before this command takes the workspace row lock. */
    void setContributorHidden(long workspaceId, long accountId, boolean hidden);
}
