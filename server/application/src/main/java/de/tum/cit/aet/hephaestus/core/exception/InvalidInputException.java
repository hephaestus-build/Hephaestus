package de.tum.cit.aet.hephaestus.core.exception;

import java.io.Serial;
import java.util.Objects;

/**
 * A value that its sender must change, with a message written for that person. {@code GlobalControllerAdvice} returns
 * the message as the detail of a 400 problem. A plain {@link IllegalArgumentException} gets a fixed detail instead,
 * because any code, a library included, throws it with a message written for developers.
 */
public class InvalidInputException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidInputException(String message) {
        super(message);
    }

    /** The cause stays on the server: the advice returns this message only. */
    public InvalidInputException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Each constructor takes the message, so it is never null. */
    @Override
    public String getMessage() {
        return Objects.requireNonNull(super.getMessage());
    }
}
