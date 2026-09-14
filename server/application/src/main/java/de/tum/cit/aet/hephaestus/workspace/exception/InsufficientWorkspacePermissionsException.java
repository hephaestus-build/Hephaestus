package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;

public class InsufficientWorkspacePermissionsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InsufficientWorkspacePermissionsException(String workspaceSlug, String message) {
        super(String.format("Insufficient permissions to access workspace '%s': %s", workspaceSlug, message));
    }
}
