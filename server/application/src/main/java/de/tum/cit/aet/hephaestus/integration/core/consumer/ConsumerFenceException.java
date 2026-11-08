package de.tum.cit.aet.hephaestus.integration.core.consumer;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** A consumer fence could not make sure that no replica handles a message of its consumer. */
public final class ConsumerFenceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    ConsumerFenceException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
