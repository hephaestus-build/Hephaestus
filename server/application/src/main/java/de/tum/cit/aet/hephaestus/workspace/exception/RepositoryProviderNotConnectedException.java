package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;

/** A repository was added by hand to a workspace with no active GitHub or GitLab connection. */
public class RepositoryProviderNotConnectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RepositoryProviderNotConnectedException(String workspaceSlug) {
        super("Workspace '" + workspaceSlug + "' has no active GitHub or GitLab connection. "
                + "Connect one before adding repositories to monitor.");
    }
}
