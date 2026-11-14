package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.List;
import java.util.Optional;

public interface AccountMembershipQuery {
    List<MembershipView> membershipsForAccount(long accountId);

    List<Long> administratorAccountIds(long workspaceId);

    /** The active accounts that own the workspace now. */
    List<Long> ownerAccountIds(long workspaceId);

    /** The workspace's address and name, unless it is purged. */
    Optional<WorkspaceView> workspace(long workspaceId);

    boolean isAdministrator(long workspaceId, long accountId);

    boolean isActiveVisibleMember(long workspaceId, long accountId);

    record WorkspaceView(String workspaceSlug, String workspaceName) {}

    record MembershipView(Long workspaceId, String workspaceSlug, String workspaceName, Role role) {}

    enum Role {
        OWNER,
        ADMIN,
        MEMBER
    }
}
