package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.Serial;

/** Thrown when a sandbox execution is cancelled before completion. */
public class SandboxCancelledException extends SandboxException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SandboxCancelledException(String message) {
        super(message);
    }
}
