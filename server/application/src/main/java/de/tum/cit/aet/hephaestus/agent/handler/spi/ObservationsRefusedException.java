package de.tum.cit.aet.hephaestus.agent.handler.spi;

import java.io.Serial;

/**
 * A review whose observations the server will not record, either because the evidence does not
 * support them or because recording them is no longer permitted. This is a review-stage decision,
 * not a machinery failure: the sandbox stops rather than repeating a refused submission.
 *
 * <p>The reason code is a stable, lowercase name for the refusal, for metrics and logs — never the
 * message, which names one job.
 */
public class ObservationsRefusedException extends JobDeliveryException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String reasonCode;
    private final String reason;

    public ObservationsRefusedException(String reasonCode, String reason) {
        super(reason);
        this.reasonCode = reasonCode;
        this.reason = reason;
    }

    public String reasonCode() {
        return reasonCode;
    }

    /** The refusal in words, always present — unlike {@code getMessage()}, which a caller must null-check. */
    public String reason() {
        return reason;
    }
}
