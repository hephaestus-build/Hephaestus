package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectionQuery implements ConnectionQueries {
    private final ConnectionRepository connections;

    @Override
    @WorkspaceAgnostic("Heals Outline webhook routes across workspaces; each registration binds its workspace")
    public List<WebhookRoute> findActiveOutlineRoutes() {
        return connections.findActiveOutlineRoutes().stream()
                .map(row -> new WebhookRoute(row.getWorkspaceId(), row.getRoute()))
                .toList();
    }

    @Override
    public boolean hasActiveSourceInstance(long workspaceId, IntegrationKind kind, String origin) {
        return connections.findActiveWorkspaceConfigs(List.of(workspaceId)).stream()
                .filter(connection -> connection.kind() == kind)
                .anyMatch(connection -> PersonSourceNamespace.from(
                                kind.name(), SourceProviderNamespaces.configuredUrl(connection.config()))
                        .flatMap(namespace -> ScmOrigin.of(namespace.serverUrl()))
                        .equals(Optional.of(origin)));
    }

    @Override
    public List<ConnectionView> findByWorkspaceId(long workspaceId) {
        return connections.findViewsByWorkspaceId(workspaceId);
    }

    @Override
    @WorkspaceAgnostic("Enumerates subscribed workspaces; each subscriber check binds its workspace")
    public List<Long> findWorkspaceIdsByKindAndState(IntegrationKind kind, IntegrationState state) {
        return connections.findWorkspaceIdsByKindAndState(kind, state);
    }

    @Override
    public Optional<ConnectionSnapshot> findActive(long workspaceId, IntegrationKind kind) {
        return connections.findViewsInState(workspaceId, kind, IntegrationState.ACTIVE, Limit.of(1)).stream()
                .findFirst();
    }

    @Override
    public Optional<ConnectionConfig> findActiveOrSuspendedConfig(long workspaceId, IntegrationKind kind) {
        return findActive(workspaceId, kind)
                .or(() ->
                        connections
                                .findViewsInState(workspaceId, kind, IntegrationState.SUSPENDED, Limit.of(1))
                                .stream()
                                .findFirst())
                .map(ConnectionSnapshot::config);
    }

    @Override
    public boolean hasConnectionInState(long workspaceId, IntegrationKind kind, IntegrationState state) {
        return !connections
                .findViewsInState(workspaceId, kind, state, Limit.of(1))
                .isEmpty();
    }
}
