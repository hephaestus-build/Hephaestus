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
        return connections.findByWorkspaceIdAndState(workspaceId, IntegrationState.ACTIVE).stream()
                .filter(connection -> connection.getKind() == kind)
                .anyMatch(connection -> PersonSourceNamespace.from(
                                kind.name(), SourceProviderNamespaces.configuredUrl(connection.getConfig()))
                        .flatMap(namespace -> ScmOrigin.of(namespace.serverUrl()))
                        .equals(Optional.of(origin)));
    }

    @Override
    public List<ConnectionView> findByWorkspaceId(long workspaceId) {
        return connections.findByWorkspaceId(workspaceId).stream()
                .map(ConnectionQuery::view)
                .toList();
    }

    @Override
    @WorkspaceAgnostic("Enumerates subscribed workspaces; each subscriber check binds its workspace")
    public List<Long> findWorkspaceIdsByKindAndState(IntegrationKind kind, IntegrationState state) {
        return connections.findWorkspaceIdsByKindAndState(kind, state);
    }

    private static ConnectionView view(Connection connection) {
        return new ConnectionView(
                connection.getId(),
                connection.getKind(),
                connection.getState(),
                connection.getConfig(),
                connection.getInstanceKey(),
                connection.getDisplayName());
    }
}
