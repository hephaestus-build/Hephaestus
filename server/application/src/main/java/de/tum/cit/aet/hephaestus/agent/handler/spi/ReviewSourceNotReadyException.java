package de.tum.cit.aet.hephaestus.agent.handler.spi;

import java.io.Serial;

/**
 * A required review input, or custody of the job's evidence folder, is temporarily unavailable.
 * The original job can retry within the existing limit before model execution.
 */
public class ReviewSourceNotReadyException extends JobPreparationException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ReviewSourceNotReadyException(String message) {
        super(message);
    }
}
