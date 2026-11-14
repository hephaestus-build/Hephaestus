package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records that a disconnect erased a connection's local data, once its transaction commits. An erasure recovery
 * manifest lists these after its recovery point (backup-restore.mdx), so a rolled-back erasure records nothing.
 */
public final class ConnectionErasures {
    private static final Logger log = LoggerFactory.getLogger(ConnectionErasures.class);

    private ConnectionErasures() {}

    public static void record(Connection erased) {
        long erasedId = Objects.requireNonNull(erased.getId());
        long erasedWorkspaceId = erased.getWorkspace().getId();
        TransactionCallbacks.afterCommit(() -> log.atInfo()
                .addKeyValue("event.name", "connection.erased")
                .addKeyValue("connection.id", erasedId)
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, erasedWorkspaceId)
                .log("Connection disconnected and its local data erased"));
    }
}
