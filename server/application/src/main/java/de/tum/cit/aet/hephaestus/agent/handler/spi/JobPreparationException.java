package de.tum.cit.aet.hephaestus.agent.handler.spi;

import java.io.Serial;

/**
 * Thrown when a {@link JobTypeHandler} fails to prepare context for a job.
 *
 * <p>The executor catches this to mark the job as {@code FAILED} with a clear,
 * handler-provided reason. Unchecked so it can propagate through lambda boundaries
 * (e.g. stream operations, functional interfaces).
 */
public class JobPreparationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JobPreparationException(String message) {
        super(message);
    }

    public JobPreparationException(String message, Throwable cause) {
        super(message, cause);
    }
}
