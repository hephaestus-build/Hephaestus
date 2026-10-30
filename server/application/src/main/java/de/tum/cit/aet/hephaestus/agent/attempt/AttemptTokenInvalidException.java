package de.tum.cit.aet.hephaestus.agent.attempt;

import java.io.Serial;

/**
 * Thrown by {@link AttemptTokenVerifier} on any failure. The detail message is for log-side
 * diagnosis only — the gateway answers every variant with one 401, so a caller learns nothing
 * about why a token failed. {@link #getReasonTag()} carries the metric-friendly reason.
 */
public class AttemptTokenInvalidException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String reasonTag;

    public AttemptTokenInvalidException(String message, String reasonTag) {
        super(message);
        this.reasonTag = reasonTag;
    }

    public AttemptTokenInvalidException(String message, String reasonTag, Throwable cause) {
        super(message, cause);
        this.reasonTag = reasonTag;
    }

    public String getReasonTag() {
        return reasonTag;
    }
}
