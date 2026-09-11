package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.Serial;

/** Unchecked exception for interactive-sandbox infrastructure failures. */
public class InteractiveSandboxException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InteractiveSandboxException(String message) {
        super(message);
    }

    public InteractiveSandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
