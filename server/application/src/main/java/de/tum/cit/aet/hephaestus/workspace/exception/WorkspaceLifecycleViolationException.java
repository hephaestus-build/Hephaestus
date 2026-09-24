package de.tum.cit.aet.hephaestus.workspace.exception;

import java.io.Serial;

/**
 * Signals that a requested lifecycle transition violates workspace invariants
 * (e.g., attempting to resume or suspend a purged workspace).
 */
public class WorkspaceLifecycleViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WorkspaceLifecycleViolationException(String message) {
        super(message);
    }
}
