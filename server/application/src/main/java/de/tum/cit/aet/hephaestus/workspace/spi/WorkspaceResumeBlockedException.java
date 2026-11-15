package de.tum.cit.aet.hephaestus.workspace.spi;

import java.io.Serial;

public class WorkspaceResumeBlockedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WorkspaceResumeBlockedException(String message) {
        super(message);
    }
}
