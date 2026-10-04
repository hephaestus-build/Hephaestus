package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when repository management operations (add/remove) are not allowed.
 * This applies to workspaces managed by GitHub App Installations, where repositories
 * are automatically synced based on the installation's repository selection.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RepositoryManagementNotAllowedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String workspaceSlug;

    public RepositoryManagementNotAllowedException(String workspaceSlug) {
        super("Repository management is not allowed for workspace “" + workspaceSlug
                + "”. "
                + "A GitHub App installation manages this workspace. "
                + "The server syncs repositories automatically from the installation configuration.");
        this.workspaceSlug = workspaceSlug;
    }

    /**
     * Get the workspace slug that triggered this exception.
     *
     * @return the workspace slug
     */
    public String getWorkspaceSlug() {
        return workspaceSlug;
    }
}
