package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.Serial;

/**
 * A {@link SandboxException} whose cause is PROVABLY transient infrastructure — a Docker daemon call
 * or sandbox I/O that failed, timed out, or was interrupted. This subtype is what makes a failed job
 * retryable, so throw it only where the failure can self-heal; a validation, configuration, or
 * unknown-defect failure would fail identically on every retry and must stay a plain
 * {@link SandboxException}.
 */
public class SandboxInfrastructureException extends SandboxException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SandboxInfrastructureException(String message) {
        super(message);
    }

    public SandboxInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
