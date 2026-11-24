package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberships {
    Optional<WorkspaceRole> activeRole(long workspaceId, long accountId);

    long accountIdForUser(long scmUserId);

    /** The team workspaces where the account has a membership row, also one it left. A personal space is none of them. */
    List<Long> teamWorkspaceIds(long accountId);

    /** Whether the account has a membership row in the workspace, also one it left. */
    boolean hasMembership(long workspaceId, long accountId);

    /** The caller holds its capture and account-write fences before this command takes the workspace row lock. */
    void setContributorHidden(long workspaceId, long accountId, boolean hidden);
}
