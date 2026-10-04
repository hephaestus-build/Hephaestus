package de.tum.cit.aet.hephaestus.integration.core.connection;

import java.io.Serial;

/**
 * An admin disconnect was rejected because the connection still has a sync job in flight.
 *
 * <p>Raised only by {@code ConnectionService#disconnect}, which has already requested that job's
 * cancellation — so this is a "retry shortly", not a dead end. System-driven transitions (workspace
 * purge, vendor uninstall) are authoritative and never raise it.
 */
public class ConnectionBusyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long connectionId;
    private final long jobId;

    public ConnectionBusyException(long connectionId, long jobId) {
        super("Connection " + connectionId
                + " has an active sync job "
                + jobId
                + ". Hephaestus asked the job to stop. Disconnect again when it has stopped.");
        this.connectionId = connectionId;
        this.jobId = jobId;
    }

    public long connectionId() {
        return connectionId;
    }

    public long jobId() {
        return jobId;
    }
}
