package de.tum.cit.aet.hephaestus.integration.core.consumer;

import java.io.Serial;
import java.time.Duration;

/**
 * A handler outlived the handler timeout. The poison handling counts it as an unknown failure: it retries the event
 * with backoff and, once the retries are spent, stores it for replay with this class as its error (ADR 0013).
 */
public final class HandlerTimeoutException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Carries no cause: the dead-letter record names its deepest cause, which must be this class. */
    HandlerTimeoutException(Duration timeout) {
        super("The handler did not finish within " + timeout);
    }
}
