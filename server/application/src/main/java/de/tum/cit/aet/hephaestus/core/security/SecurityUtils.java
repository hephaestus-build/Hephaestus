package de.tum.cit.aet.hephaestus.core.security;

import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Utility class for Spring Security.
 */
public final class SecurityUtils {

    /**
     * The instance-admin authority string minted by {@code JwtPrincipalFactory} for
     * {@code Account.AppRole.APP_ADMIN} accounts and gated on here by {@link #isSuperAdmin()}. It is a
     * security contract shared between that producer and this consumer — keep the two in agreement.
     */
    public static final String APP_ADMIN_AUTHORITY = "app_admin";

    private SecurityUtils() {}

    /** Native account id from the JWT subject, independent of the sign-in provider. */
    public static Optional<Long> getCurrentAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(subject.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Check if the current user is an instance super-admin (Account.AppRole APP_ADMIN).
     * Such users are elevated to workspace-ADMIN (never OWNER) on any active workspace by
     * {@code WorkspaceContextFilter} — including workspaces where they hold no membership. This method
     * itself only checks for the presence of the authority. The instance-admin authority is the
     * namespaced {@code app_admin} the issuer mints
     * for APP_ADMIN accounts (see {@code JwtPrincipalFactory}) — deliberately distinct from the
     * per-workspace {@code admin} role, which is membership-derived and never appears in the JWT.
     *
     * <p>False during a read-only user view: that request carries the viewed member's workspace roles, and
     * instance-admin elevation would replace them with the administrator's.
     *
     * @return true if the current user has the {@code app_admin} authority and is not viewing as another user
     */
    public static boolean isSuperAdmin() {
        if (UserViewContextHolder.get() != null) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }

        // Flat `roles` claim on the Hephaestus-issued JWT (ADR 0017).
        var rolesObj = jwt.getClaims().get("roles");
        return rolesObj instanceof List<?> roles && roles.contains(APP_ADMIN_AUTHORITY);
    }
}
