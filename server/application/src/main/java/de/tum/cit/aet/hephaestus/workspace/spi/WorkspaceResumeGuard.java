package de.tum.cit.aet.hephaestus.workspace.spi;

/**
 * Refuses an admin's resume of a suspended workspace while a provider keeps it suspended. Such a resume would
 * reopen the workspace to its members while nothing can sync, and the provider would suspend it again.
 */
public interface WorkspaceResumeGuard {
    /** Throws {@link WorkspaceResumeBlockedException} with the step that resumes the workspace instead. */
    void verifyResumable(long workspaceId);
}
