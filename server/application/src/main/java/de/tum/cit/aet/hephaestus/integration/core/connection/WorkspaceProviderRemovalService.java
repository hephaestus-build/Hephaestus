package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderRemoval;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disconnects each connection of a workspace that is being removed as an admin disconnect does, keeping its credential,
 * and sends its provider removals before the purge releases that credential. A removal that does not finish in time
 * stays recorded under Provider removals, so a removal never leaves a provider resource behind silently.
 */
@Component
@ConditionalOnServerRole
class WorkspaceProviderRemovalService implements WorkspaceProviderRemoval {

    /** How long a workspace removal waits for its provider removals in total; each call has its own client timeout. */
    static final Duration REMOVAL_BUDGET = Duration.ofSeconds(30);

    private final ConnectionRepository connections;
    private final ConnectionService connectionService;
    private final ProviderTeardown teardown;
    private final Map<IntegrationKind, ConnectionStrategy> strategies = new EnumMap<>(IntegrationKind.class);
    private final TransactionTemplate transactions;
    private final Clock clock;

    WorkspaceProviderRemovalService(
            ConnectionRepository connections,
            ConnectionService connectionService,
            ProviderTeardown teardown,
            List<ConnectionStrategy> strategyBeans,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.connections = connections;
        this.connectionService = connectionService;
        this.teardown = teardown;
        strategyBeans.forEach(strategy -> strategies.put(strategy.kind(), strategy));
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void removeBeforePurge(long workspaceId) {
        Instant deadline = clock.instant().plus(REMOVAL_BUDGET);
        for (Connection connection : connections.findByWorkspaceId(workspaceId)) {
            ConnectionStrategy strategy = strategies.get(connection.getKind());
            // Without its strategy nothing here can erase the integration's data: the purge disconnects it, and its
            // removal waits for authorization.
            if (strategy == null) continue;
            if (connection.getState() != IntegrationState.UNINSTALLED) {
                transactions.executeWithoutResult(status -> connectionService.disconnectForRemoval(
                        connection,
                        // Its own event, new on each attempt: a purge that fails afterwards leaves the workspace, and a
                        // reconnected connection must disconnect again on the next attempt. The audit's unique key
                        // holds the connection, and its correlation column has 64 characters.
                        new ConnectionService.TransitionRequest(
                                IntegrationState.UNINSTALLED,
                                "WORKSPACE_REMOVAL",
                                "SYSTEM",
                                "workspace-purge",
                                "workspace-removal-" + UUID.randomUUID(),
                                "Disconnect before the workspace removal"),
                        () -> strategy.eraseLocalData(new IntegrationRef(
                                connection.getKind(), workspaceId, connection.getInstanceKey(), connection.getId()))));
            }
            // An earlier disconnect's removals run here too while their credential remains, unless one waits for
            // its next attempt: the purge then leaves it waiting for authorization.
            teardown.tearDownNow(workspaceId, connection.getId(), deadline);
        }
    }
}
