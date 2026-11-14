package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;

public class LastOwnerRemovalException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * A change would leave the workspace without an owner: removing, demoting or the leave of its last owner. Every
     * path says the same, under the problem type {@code workspace-owner-required}.
     */
    public LastOwnerRemovalException(String workspaceName) {
        super(workspaceName + " needs an owner. Make another member an owner first, or delete the workspace.");
    }
}
