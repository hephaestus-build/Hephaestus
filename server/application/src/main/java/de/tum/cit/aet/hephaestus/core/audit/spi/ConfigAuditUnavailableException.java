package de.tum.cit.aet.hephaestus.core.audit.spi;

import java.io.Serial;

/**
 * The audit trail could not record a change, so the change must not commit.
 *
 * <p>A distinct type because this is a server-side wiring fault, not a client mistake: mapped to a 4xx
 * it would sit outside the 5xx error budget and page nobody, so a fail-closed guarantee would fail
 * quietly at the observability layer.
 */
public class ConfigAuditUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConfigAuditUnavailableException(String message) {
        super(message);
    }
}
