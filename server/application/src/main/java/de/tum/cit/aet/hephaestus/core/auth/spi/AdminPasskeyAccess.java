package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Requires assurance after workspace role authorization, before an admin capability is used. */
public interface AdminPasskeyAccess {
    boolean assuranceEnforced();

    boolean workspaceAdminRequired();

    void requireFresh();

    void requireWorkspaceAdmin(boolean workspaceRequired, boolean elevated, boolean sensitive);

    /**
     * Whether the policy would require a passkey proof of this account for admin work in a workspace. An
     * MCP client cannot hold such a proof, so its consent is refused where this holds (ADR 0110 decision 8).
     */
    boolean workspaceAdminNeedsProof(long accountId, boolean workspaceRequired);
}
