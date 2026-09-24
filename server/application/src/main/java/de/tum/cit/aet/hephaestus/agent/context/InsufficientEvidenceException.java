package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import java.io.Serial;

public final class InsufficientEvidenceException extends JobPreparationException {

    @Serial
    private static final long serialVersionUID = 1L;

    // The preparation coordinator consumes this evidence in-process; it is never sent via Java serialization.
    @SuppressWarnings("serial")
    private final PreparedJobInputs preparedInputs;

    public InsufficientEvidenceException(String message, PreparedJobInputs preparedInputs) {
        super(message);
        this.preparedInputs = preparedInputs;
    }

    public PreparedJobInputs preparedInputs() {
        return preparedInputs;
    }
}
