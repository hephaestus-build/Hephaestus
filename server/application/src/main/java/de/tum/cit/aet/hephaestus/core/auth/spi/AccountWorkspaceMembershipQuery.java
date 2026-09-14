package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.List;

/** Account-owned workspace membership, resolved through verified identities rather than usernames. */
public interface AccountWorkspaceMembershipQuery {
    /**
     * Returns one row per workspace with the account's strongest membership role. The first linked actor with membership
     * represents the account for attribution, independently of role changes or later identity links. Callers must supply an account
     * obtained from authenticated identity evidence, not a username.
     */
    List<WorkspaceMembershipView> membershipsForAccount(Long accountId);

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
