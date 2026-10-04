package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.io.Serial;

/** Retryable refusal: no worker can admit this session now. */
public final class MentorBusyException extends InteractiveSandboxException {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String USER_MESSAGE = "Heph is busy. Try again.";

    public MentorBusyException() {
        super(USER_MESSAGE);
    }
}
