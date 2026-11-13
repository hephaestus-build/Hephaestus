package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Connection metadata for consumers outside the owning module. Never includes credentials. */
public interface ConnectionQueries {
    List<WebhookRoute> findActiveOutlineRoutes();

    boolean hasActiveSourceInstance(long workspaceId, IntegrationKind kind, String origin);

    List<ConnectionView> findByWorkspaceId(long workspaceId);

    List<Long> findWorkspaceIdsByKindAndState(IntegrationKind kind, IntegrationState state);

    record ConnectionView(
            long id,
            IntegrationKind kind,
            IntegrationState state,
            ConnectionConfig config,
            @Nullable String instanceKey,
            @Nullable String displayName) {}

    record WebhookRoute(long workspaceId, @Nullable String route) {}
}
