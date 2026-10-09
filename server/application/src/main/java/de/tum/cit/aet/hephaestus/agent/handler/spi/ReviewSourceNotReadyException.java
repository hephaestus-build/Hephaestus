package de.tum.cit.aet.hephaestus.agent.handler.spi;

import java.io.Serial;

/** A required provider source is still being prepared; the original job can retry before model execution. */
public class ReviewSourceNotReadyException extends JobPreparationException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ReviewSourceNotReadyException(String message) {
        super(message);
    }
}
