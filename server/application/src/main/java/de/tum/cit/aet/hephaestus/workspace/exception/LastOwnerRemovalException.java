package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;

public class LastOwnerRemovalException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private LastOwnerRemovalException(String message) {
        super(message);
    }

    /** A change would take the owner role from the last owner. */
    public static LastOwnerRemovalException removing(String workspaceSlug) {
        return new LastOwnerRemovalException(String.format(
                "You cannot remove the last OWNER role from workspace '%s'. Assign another user as OWNER first.",
                workspaceSlug));
    }

    /** The last owner tried to leave. */
    public static LastOwnerRemovalException leaving(String workspaceSlug) {
        return new LastOwnerRemovalException(String.format(
                "You are the last owner of %s. Make another member an owner, or delete the workspace.", workspaceSlug));
    }
}
