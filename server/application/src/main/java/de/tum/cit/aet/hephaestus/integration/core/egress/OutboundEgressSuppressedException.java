package de.tum.cit.aet.hephaestus.integration.core.egress;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import java.io.Serial;

public class OutboundEgressSuppressedException extends FeedbackDeliveryException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OutboundEgressSuppressedException(String operation) {
        super("Silent mode blocked the outbound operation: " + operation);
    }

    public OutboundEgressSuppressedException(String operation, Throwable cause) {
        super(
                "Hephaestus blocked the outbound operation because it could not read the silent mode state: "
                        + operation,
                cause);
    }
}
