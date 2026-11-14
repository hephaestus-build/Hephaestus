package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import org.jspecify.annotations.Nullable;

/**
 * The tenant root as the source providers bind and shape it. Every command locks the workspace row, changes it inside
 * the workspace module and joins the caller's transaction. {@link WorkspaceLookup} and {@link WorkspaceLocks} find it.
 */
public interface WorkspaceRoots {
    /**
     * @return whether the status changed
     * @throws de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException if the workspace is purged
     */
    boolean suspend(long workspaceId);

    /**
     * @return whether the status changed
     * @throws de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException if the workspace is purged
     */
    boolean resume(long workspaceId);

    void selectRepositories(long workspaceId, RepositorySelection selection);

    void renameAccount(long workspaceId, String accountLogin);

    void linkOrganization(long workspaceId, long organizationId);

    /** Unbinds the organization and writes it at once, so a following count of its workspaces sees it. */
    void unlinkOrganization(long workspaceId);

    /** Creates a workspace under a slug that the caller validated and allocated. */
    Workspace create(
            String slug,
            String displayName,
            String accountLogin,
            AccountType accountType,
            @Nullable Long ownerAccountId);
}
