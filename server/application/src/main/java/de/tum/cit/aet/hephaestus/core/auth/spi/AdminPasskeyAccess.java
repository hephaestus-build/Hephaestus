package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Duration;

/** Requires assurance after workspace role authorization, before an admin capability is used. */
public interface AdminPasskeyAccess {
    boolean assuranceEnforced();

    boolean workspaceAdminRequired();

    void requireFresh();

    void requireWorkspaceAdmin(boolean workspaceRequired, boolean elevated, boolean sensitive);

    /** What the policy asks before this account lets an MCP client do admin work in a workspace (ADR 0110 decision 8). */
    enum McpConsentProof {
        /** The policy asks no passkey of this account here. */
        NONE,
        /** The account confirms with a passkey on the consent page; the grant then holds that proof for its bound. */
        PASSKEY,
        /** The account's passkey recovery is pending, so it cannot delegate admin work. */
        REFUSED_RECOVERY_PENDING,
        /** The instance requires a passkey of its instance admins, and that proof is never delegated. */
        REFUSED_INSTANCE_ADMIN,
    }

    McpConsentProof mcpConsentProof(long accountId, boolean workspaceRequired);

    /** Whether the current browser request carries a passkey proof within the step-up window. */
    boolean freshPasskeyProof();

    /**
     * How long a passkey proof given at MCP consent stands for the grant's calls: one hour, or the admin proof window if
     * the operator set a shorter one. The client session ends at this bound.
     */
    Duration mcpPasskeyBound();
}
