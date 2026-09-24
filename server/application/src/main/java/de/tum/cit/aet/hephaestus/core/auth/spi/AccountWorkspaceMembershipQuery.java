package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.List;
import java.util.Set;

/**
 * Cross-module read query: which workspaces is the principal a member of, and with what role?
 *
 * <p>Workspace membership lives in the {@code workspace} module, keyed by the SCM
 * {@code user_id} (login bridge), not by {@code Account}. The {@code core.auth} module owns the
 * {@code Account → login} mapping (via {@code IdentityLink.usernameAtSignup}) and supplies the
 * resolved login set; the {@code workspace} module resolves {@code login → User → membership}
 * internally. This keeps the boundary clean: {@code core.auth} never imports workspace domain
 * types, and {@code workspace} never imports auth domain types — the contract lives here and is
 * implemented in {@code workspace}.
 */
public interface AccountWorkspaceMembershipQuery {
    /** Current memberships through active identity links and their stable actor IDs; never a login-name join. */
    List<WorkspaceMembershipView> membershipsForAccount(long accountId);

    List<Long> administratorAccountIds(long workspaceId);

    boolean isAdministrator(long workspaceId, long accountId);

    /**
     * @param logins the principal's git-provider logins (case-insensitive), resolved from its
     *               active identity links; empty input yields an empty result
     * @return one row per workspace the principal belongs to (across all supplied logins),
     *         deduplicated by workspace
     */
    List<WorkspaceMembershipView> membershipsForLogins(Set<String> logins);

    /**
     * A single workspace membership, flattened for export. Contains no SCM-user PII beyond what
     * the principal already owns (the workspace they belong to + their role + the id of their own
     * member row). {@code memberId} is the SCM {@code User} id the membership hangs off — the handle
     * integration resolvers need to attribute provider-native activity to a workspace member without
     * reaching into the SCM schema themselves.
     */
    record WorkspaceMembershipView(
            Long workspaceId,
            String workspaceSlug,
            String workspaceName,
            @org.jspecify.annotations.Nullable String role,
            @org.jspecify.annotations.Nullable Long memberId) {}
}
