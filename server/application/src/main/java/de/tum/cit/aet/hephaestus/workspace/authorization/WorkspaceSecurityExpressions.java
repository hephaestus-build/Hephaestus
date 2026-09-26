package de.tum.cit.aet.hephaestus.workspace.authorization;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import org.springframework.stereotype.Component;

/**
 * Custom Spring Security expressions for workspace authorization.
 * Used in @PreAuthorize annotations on controller methods.
 */
@Component("workspaceSecure")
public class WorkspaceSecurityExpressions {

    private final WorkspaceAccessService accessService;

    /**
     * Constructor injection for access service.
     *
     * @param accessService the workspace access service
     */
    public WorkspaceSecurityExpressions(WorkspaceAccessService accessService) {
        this.accessService = accessService;
    }

    /**
     * Check if user is authenticated and has ADMIN role or higher in the workspace.
     * Use: @RequireAtLeastWorkspaceAdmin
     *
     * @return true if user is an admin or owner
     */
    public boolean isAdmin() {
        return accessService.isAdmin();
    }

    /**
     * Check if user is authenticated and has OWNER role in the workspace.
     * Use: @RequireWorkspaceOwner
     *
     * @return true if user is the owner
     */
    public boolean isOwner() {
        return accessService.isOwner();
    }

    /**
     * Check if user is authenticated and has at least MEMBER role in the workspace.
     * Use in SpEL: {@code @PreAuthorize("@workspaceSecure.isMember()")}.
     *
     * @return true if user has any workspace role (MEMBER, ADMIN, or OWNER)
     */
    public boolean isMember() {
        return accessService.isMember();
    }

    /**
     * Like {@link #isMember()}, but instance-admin elevation does not count: for what a workspace offers
     * its own members, such as Heph. Use in SpEL: {@code @PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")}.
     *
     * @return true if the caller (or, in a user view, the viewed member) holds a workspace membership
     */
    public boolean isMemberWithoutElevation() {
        return accessService.isMemberWithoutElevation();
    }

    /**
     * Check if user has permission to manage the specified role.
     * OWNER can manage all roles, ADMIN can manage ADMIN and MEMBER.
     * Can be used in SpEL: @PreAuthorize("@workspaceSecure.canManageRole(#role)")
     *
     * @param role Role to manage
     * @return true if user can manage this role
     */
    public boolean canManageRole(WorkspaceRole role) {
        return accessService.canManageRole(role);
    }
}
