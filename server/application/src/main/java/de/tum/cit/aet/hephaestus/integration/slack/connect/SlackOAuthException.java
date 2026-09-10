package de.tum.cit.aet.hephaestus.integration.slack.connect;

import java.io.Serial;

public class SlackOAuthException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SlackOAuthException(String message) {
        super(message);
    }

    public SlackOAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
