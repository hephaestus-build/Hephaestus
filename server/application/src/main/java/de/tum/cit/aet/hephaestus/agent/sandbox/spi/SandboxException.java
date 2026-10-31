package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.Serial;

/** Unchecked exception for sandbox infrastructure failures. */
public class SandboxException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
