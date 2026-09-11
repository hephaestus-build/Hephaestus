package de.tum.cit.aet.hephaestus.integration.scm.github.sync.exception;

import java.io.Serial;

/**
 * Exception thrown when all retry attempts for a sync operation have been exhausted.
 * <p>
 * This typically occurs when a transient failure persists across all configured
 * retry attempts during data synchronization.
 */
public class SyncRetriesExhaustedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SyncRetriesExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }

    public SyncRetriesExhaustedException(String message) {
        super(message);
    }
}
