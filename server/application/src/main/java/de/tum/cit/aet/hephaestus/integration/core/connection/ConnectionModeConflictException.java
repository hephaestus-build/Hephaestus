package de.tum.cit.aet.hephaestus.integration.core.connection;

import java.io.Serial;

/**
 * A write that the connection's current mode cannot take, such as a bearer token for a GitHub App
 * installation, which runs on its installation and stores no token. A state conflict, answered as one.
 */
public class ConnectionModeConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConnectionModeConflictException(String message) {
        super(message);
    }
}
