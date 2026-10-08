package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Requires assurance after workspace role authorization, before an admin capability is used. */
public interface AdminPasskeyAccess {
    boolean workspaceAdminRequired();

    void requireFresh();

    void requireWorkspaceAdmin(boolean workspaceRequired, boolean elevated, boolean sensitive);
}
