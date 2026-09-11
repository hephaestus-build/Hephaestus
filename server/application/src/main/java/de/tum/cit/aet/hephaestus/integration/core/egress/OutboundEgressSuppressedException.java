package de.tum.cit.aet.hephaestus.integration.core.egress;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import java.io.Serial;

public class OutboundEgressSuppressedException extends FeedbackDeliveryException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OutboundEgressSuppressedException(String operation) {
        super("Instance Silent Mode suppressed outbound operation: " + operation);
    }

    public OutboundEgressSuppressedException(String operation, Throwable cause) {
        super("Outbound operation blocked because Silent Mode state could not be read: " + operation, cause);
    }
}
