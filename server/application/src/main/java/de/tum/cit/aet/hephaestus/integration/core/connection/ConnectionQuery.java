package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import java.util.List;
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
}
