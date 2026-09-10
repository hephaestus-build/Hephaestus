package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;

public class LastOwnerRemovalException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LastOwnerRemovalException(String workspaceSlug) {
        super(String.format(
                "Cannot remove the last OWNER role from workspace '%s'. " + "Assign another user as OWNER first.",
                workspaceSlug));
    }
}
