package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import java.io.Serial;

/**
 * Thrown by {@link WorkerJwtVerifier} on any failure. The detail message is for log-side
 * diagnosis only — the handshake interceptor maps every variant to a single 401 to avoid
 * attack-surface enumeration. {@link #getReasonTag()} carries the metric-friendly reason.
 */
public class WorkerJwtInvalidException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String reasonTag;

    public WorkerJwtInvalidException(String message, String reasonTag) {
        super(message);
        this.reasonTag = reasonTag;
    }

    public WorkerJwtInvalidException(String message, String reasonTag, Throwable cause) {
        super(message, cause);
        this.reasonTag = reasonTag;
    }

    public String getReasonTag() {
        return reasonTag;
    }
}
