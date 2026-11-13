package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ConnectionPurgeContributor implements WorkspacePurgeContributor {

    static final int PURGE_ORDER = -300;

    private static final Logger log = LoggerFactory.getLogger(ConnectionPurgeContributor.class);

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final ObjectProvider<ProviderTeardown> providerTeardown;
    private final Map<IntegrationKind, ConnectionStrategy> strategies;

    public ConnectionPurgeContributor(
            ConnectionRepository connectionRepository,
            ConnectionService connectionService,
            ObjectProvider<ProviderTeardown> providerTeardown,
            List<ConnectionStrategy> strategyBeans) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.providerTeardown = providerTeardown;
        this.strategies = new EnumMap<>(IntegrationKind.class);
        for (ConnectionStrategy strategy : strategyBeans) {
            if (strategies.put(strategy.kind(), strategy) != null) {
                throw new IllegalStateException("Duplicate ConnectionStrategy for kind=" + strategy.kind());
            }
        }
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        List<Connection> connections = connectionRepository.findByWorkspaceId(workspaceId);
        // A pending teardown is removed first: a failure then blocks the purge before anything else changes.
        connections.stream()
                .filter(c -> c.getState() == IntegrationState.UNINSTALLED && c.getProviderTeardownRequestedAt() != null)
                .forEach(this::tearDownPending);
        for (Connection c : connections) {
            if (c.getState() == IntegrationState.UNINSTALLED) {
                continue;
            }
            ConnectionStrategy strategy = strategies.get(c.getKind());
            connectionService.disconnectForErasure(
                    c,
                    new ConnectionService.TransitionRequest(
                            IntegrationState.UNINSTALLED,
                            "WORKSPACE_PURGED",
                            "SYSTEM",
                            "workspace-purge",
                            "workspace-" + workspaceId + "-purge-" + c.getId(),
                            "Cascade from workspace PURGE"),
                    () -> {
                        if (strategy != null) {
                            strategy.eraseLocalData(
                                    new IntegrationRef(c.getKind(), workspaceId, c.getInstanceKey(), c.getId()));
                        }
                    });
        }
    }

    /** A disconnect that is still removing its footprint keeps a credential, which the erasure must not leave behind. */
    private void tearDownPending(Connection connection) {
        ProviderTeardown teardown = providerTeardown.getIfAvailable();
        try {
            if (teardown == null) {
                throw new IllegalStateException("Provider teardown is unavailable in this runtime role");
            }
            teardown.tearDownForErasure(connection);
        } catch (RuntimeException e) {
            log.warn(
                    "Pending provider teardown failed during workspace purge: kind={}, connectionId={}, error={}",
                    connection.getKind(),
                    connection.getId(),
                    e.getClass().getSimpleName());
            throw new WorkspacePurgeBlockedException(
                    "Hephaestus could not record the pending " + providerName(connection.getKind())
                            + " removal, so it deleted no local data. Try again when the database is available.",
                    e);
        }
    }

    private static String providerName(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> "GitHub";
            case GITLAB -> "GitLab";
            case SLACK -> "Slack";
            case OUTLINE -> "Outline";
        };
    }

    @Override
    public int getOrder() {
        return PURGE_ORDER;
    }
}
