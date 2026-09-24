package de.tum.cit.aet.hephaestus.agent.handler.spi;

import java.io.Serial;

public final class JobDeliverySuppressedException extends JobDeliveryException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JobDeliverySuppressedException(String message, Throwable cause) {
        super(message, cause);
    }
}
