package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.io.Serial;

/**
 * Thrown by {@link SummaryChannel}, {@link InlineFeedbackChannel}, and
 * {@link ApprovalChannel} implementations when posting fails irrecoverably.
 *
 * <p>Vendor adapters never need to know about the agent-side
 * {@code JobDeliveryException}: they raise this exception and the agent's poster
 * wrapper translates it to the failure type its executor expects.
 *
 * <p>Unchecked so it propagates through the channel SPI's value-returning method
 * signatures without forcing a {@code throws} declaration.
 */
public class FeedbackDeliveryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FeedbackDeliveryException(String message) {
        super(message);
    }

    public FeedbackDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
