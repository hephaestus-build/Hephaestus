package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredDisconnects;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Disconnects again a connection that a database restore brought back, as the workspace purge disconnects its
 * connections: its local data is erased and its provider removal recorded, with no provider call, so it works while
 * provider egress is closed.
 */
@Component
class RestoredConnectionErasure implements RestoredDisconnects {
    private final ConnectionRepository connections;
    private final ConnectionService connectionService;
    private final Map<IntegrationKind, ConnectionStrategy> strategies = new EnumMap<>(IntegrationKind.class);

    RestoredConnectionErasure(
            ConnectionRepository connections, ConnectionService connectionService, List<ConnectionStrategy> strategies) {
        this.connections = connections;
        this.connectionService = connectionService;
        for (ConnectionStrategy strategy : strategies) this.strategies.put(strategy.kind(), strategy);
    }

    @Override
    public boolean disconnectAgain(long workspaceId, long connectionId) {
        var connection = connections.findByIdAndWorkspaceId(connectionId, workspaceId).orElse(null);
        if (connection == null || connection.getState() == IntegrationState.UNINSTALLED) return false;
        ConnectionStrategy strategy = strategies.get(connection.getKind());
        // Without its strategy nothing can erase the connection's data, so recovery stops rather than skip it.
        if (strategy == null) throw new IllegalStateException("No strategy can erase this connection's data");
        connectionService.disconnectForErasure(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.UNINSTALLED,
                        "ERASURE_RECOVERY",
                        "SYSTEM",
                        "erasure-recovery",
                        "erasure-recovery-" + connectionId,
                        "Disconnected again after a database restore"),
                () -> strategy.eraseLocalData(
                        new IntegrationRef(connection.getKind(), workspaceId, connection.getInstanceKey(), connectionId)));
        return true;
    }
}
