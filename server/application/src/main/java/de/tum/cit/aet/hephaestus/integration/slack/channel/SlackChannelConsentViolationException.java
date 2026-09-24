package de.tum.cit.aet.hephaestus.integration.slack.channel;

import java.io.Serial;

/**
 * Signals that a requested Slack channel consent transition violates the state machine — e.g. pausing a channel
 * that was never activated. Mapped by {@code SlackChannelControllerAdvice} to a {@code 409 Conflict}
 * {@code ProblemDetail}.
 */
public class SlackChannelConsentViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SlackChannelConsentViolationException(String message) {
        super(message);
    }
}
