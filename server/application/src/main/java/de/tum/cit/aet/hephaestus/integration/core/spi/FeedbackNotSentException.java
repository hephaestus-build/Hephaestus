package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.io.Serial;

/**
 * Thrown by a channel that failed before it sent its create request, so the provider cannot hold the feedback. A
 * failure of the create request itself stays a plain {@link FeedbackDeliveryException}: its outcome is unknown.
 */
public class FeedbackNotSentException extends FeedbackDeliveryException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FeedbackNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
