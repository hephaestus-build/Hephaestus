package de.tum.cit.aet.hephaestus.core.auth.spi;

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
        /** The account confirms with a passkey on the consent page; the grant then holds that proof until it ends. */
        PASSKEY,
        /** The account's passkey recovery is pending, so it cannot delegate admin work. */
        REFUSED_RECOVERY_PENDING,
    }

    McpConsentProof mcpConsentProof(long accountId, boolean workspaceRequired);

    /** Whether the current browser request carries a passkey proof within the step-up window. */
    boolean freshPasskeyProof();
}
