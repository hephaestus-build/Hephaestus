package de.tum.cit.aet.hephaestus.agent.handler.spi;

import java.io.Serial;

/**
 * An observation quoted evidence that does not appear where it said the evidence was.
 *
 * <p>Distinct from every other refusal in the evidence gate because it is the one that says nothing
 * about the run: the sources were staged, the citation was well formed and authorized, and the model
 * simply did not reproduce what it read. That discredits the claim and no other, so it is caught per
 * observation. Every other refusal — an unstaged source, a malformed citation, a missing search —
 * means the run itself cannot be trusted, and stays fatal.
 */
public class EvidenceQuoteUnverifiedException extends JobDeliveryException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EvidenceQuoteUnverifiedException(String message) {
        super(message);
    }
}
