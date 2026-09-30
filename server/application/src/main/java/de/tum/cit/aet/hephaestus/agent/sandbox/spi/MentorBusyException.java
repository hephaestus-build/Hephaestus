package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

/** Retryable refusal: no worker can admit this session now. */
public final class MentorBusyException extends InteractiveSandboxException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public MentorBusyException() {
        super("Heph is busy. Please try again.");
    }
}
