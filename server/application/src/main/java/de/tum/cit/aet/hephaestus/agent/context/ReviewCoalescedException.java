package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import java.io.Serial;

/**
 * Every ready practice was already answered by a completed review of the same code, so nothing is left to ask. Not
 * {@link InsufficientEvidenceException}: the evidence was sufficient, and the answers this run would give exist.
 */
public final class ReviewCoalescedException extends JobPreparationException {

    @Serial
    private static final long serialVersionUID = 1L;

    // The preparation coordinator consumes this evidence in-process; it is never sent via Java serialization.
    @SuppressWarnings("serial")
    private final PreparedJobInputs preparedInputs;

    public ReviewCoalescedException(String message, PreparedJobInputs preparedInputs) {
        super(message);
        this.preparedInputs = preparedInputs;
    }

    public PreparedJobInputs preparedInputs() {
        return preparedInputs;
    }
}
