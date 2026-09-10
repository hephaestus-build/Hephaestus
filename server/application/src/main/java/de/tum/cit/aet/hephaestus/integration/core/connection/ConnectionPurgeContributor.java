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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConnectionPurgeContributor implements WorkspacePurgeContributor {

    static final int PURGE_ORDER = -300;

    private static final Logger log = LoggerFactory.getLogger(ConnectionPurgeContributor.class);

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final Map<IntegrationKind, ConnectionStrategy> strategies;

    public ConnectionPurgeContributor(
            ConnectionRepository connectionRepository,
            ConnectionService connectionService,
            List<ConnectionStrategy> strategyBeans) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
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
                    () -> revokeProvider(c, strategy));
        }
    }

    private void revokeProvider(Connection connection, @Nullable ConnectionStrategy strategy) {
        if (strategy == null) {
            log.warn(
                    "Provider teardown unavailable during workspace purge: kind={}, connectionId={}; continuing with local erasure",
                    connection.getKind(),
                    connection.getId());
            return;
        }
        try {
            strategy.revokeProvider(new IntegrationRef(
                    connection.getKind(),
                    connection.getWorkspace().getId(),
                    connection.getInstanceKey(),
                    connection.getId()));
        } catch (RuntimeException e) {
            log.warn(
                    "Provider teardown failed during workspace purge: kind={}, connectionId={}, error={}",
                    connection.getKind(),
                    connection.getId(),
                    e.toString());
            throw new WorkspacePurgeBlockedException(
                    "Could not confirm disconnecting " + providerName(connection.getKind())
                            + ". No local data was deleted; retry when the provider is available.",
                    e);
        }
    }

    private static String providerName(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> "GitHub";
            case GITLAB -> "GitLab";
            case SLACK -> "Slack";
            case OUTLINE -> "Outline";
            case KEYCLOAK_DIRECTORY -> "Keycloak directory";
        };
    }

    @Override
    public int getOrder() {
        return PURGE_ORDER;
    }
}
