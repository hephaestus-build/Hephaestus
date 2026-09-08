package de.tum.cit.aet.hephaestus.workspace.authorization;

import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Checks the effective workspace context. Instance-admin elevation never grants ownership. */
@Service
public class WorkspaceAccessService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAccessService.class);

    /**
     * Requires a nonempty workspace context. Instance admins satisfy ADMIN checks, not OWNER checks;
     * the workspace filter supplies an elevated context when they have no explicit membership.
     */
    public boolean hasRole(WorkspaceRole requiredRole) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        if (context == null) {
            log.warn("Denied role check: reason=noWorkspaceContext, requiredRole={}", requiredRole);
            return false;
        }

        Set<WorkspaceRole> userRoles = context.roles();
        if (userRoles == null || userRoles.isEmpty()) {
            log.debug("Denied role check: reason=noRoles, workspaceSlug={}", context.slug());
            return false;
        }

        for (WorkspaceRole userRole : userRoles) {
            if (userRole.isAtLeast(requiredRole)) {
                return true;
            }
        }

        if (requiredRole != WorkspaceRole.OWNER && SecurityUtils.isSuperAdmin()) {
            log.debug(
                    "Granted role check: reason=superAdminElevation, requiredRole={}, workspaceSlug={}",
                    requiredRole,
                    context.slug());
            return true;
        }

        log.debug(
                "Denied role check: reason=insufficientRole, userRoles={}, requiredRole={}, workspaceSlug={}",
                userRoles,
                requiredRole,
                context.slug());
        return false;
    }

    public boolean isOwner() {
        return hasRole(WorkspaceRole.OWNER);
    }

    public boolean isAdmin() {
        return hasRole(WorkspaceRole.ADMIN);
    }

    public boolean isMember() {
        return hasRole(WorkspaceRole.MEMBER);
    }

    public boolean hasPermission(WorkspaceRole requiredRole) {
        return hasRole(requiredRole);
    }

    /** Only owners can manage OWNER roles; admins can manage the other roles. */
    public boolean canManageRole(WorkspaceRole targetRole) {
        WorkspaceContext context = WorkspaceContextHolder.getContext();
        if (context == null) {
            return false;
        }

        Set<WorkspaceRole> userRoles = context.roles();
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }

        if (userRoles.contains(WorkspaceRole.OWNER)) {
            return true;
        }

        if (userRoles.contains(WorkspaceRole.ADMIN)) {
            return targetRole != WorkspaceRole.OWNER;
        }

        return targetRole != WorkspaceRole.OWNER && SecurityUtils.isSuperAdmin();
    }
}
