package de.tum.cit.aet.hephaestus.integration.slack.connect;

import java.io.Serial;
import java.util.Objects;

public class SlackOAuthException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SlackOAuthException(String message) {
        super(message);
    }

    public SlackOAuthException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The Slack error code, or a fixed code of the client: every constructor call passes one. */
    public String error() {
        return Objects.requireNonNull(getMessage());
    }
}
