package de.tum.cit.aet.hephaestus.integration.scm.github.sync.exception;

import java.io.Serial;

/**
 * Exception thrown when a sync operation is interrupted.
 * <p>
 * This typically occurs when:
 * <ul>
 *   <li>Thread is interrupted while waiting for rate limits</li>
 *   <li>Application is shutting down during sync</li>
 *   <li>Sync operation is cancelled</li>
 * </ul>
 */
public class SyncInterruptedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SyncInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }

    public SyncInterruptedException(String message) {
        super(message);
    }
}
