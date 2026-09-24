package de.tum.cit.aet.hephaestus.workspace.spi;

import java.io.Serial;

public class WorkspacePurgeBlockedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WorkspacePurgeBlockedException(String message) {
        super(message);
    }

    public WorkspacePurgeBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
