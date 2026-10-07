package de.tum.cit.aet.hephaestus.core.auth.spi;

/** Workspace-owned resolution of the active workspace policy. */
public interface WorkspaceAdminAssurance {
    void require(boolean sensitive);
}
