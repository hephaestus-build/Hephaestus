package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.Serial;

/**
 * Unchecked exception for sandbox infrastructure failures.
 *
 * <p>Wraps docker-java exceptions and other infrastructure errors so that callers of {@link
 * SandboxManager} do not depend on Docker-specific types.
 */
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
