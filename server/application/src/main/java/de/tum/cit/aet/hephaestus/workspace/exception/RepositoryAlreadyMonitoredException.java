package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;

public class RepositoryAlreadyMonitoredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RepositoryAlreadyMonitoredException(String nameWithOwner) {
        super("The repository “" + nameWithOwner + "” is already monitored.");
    }
}
