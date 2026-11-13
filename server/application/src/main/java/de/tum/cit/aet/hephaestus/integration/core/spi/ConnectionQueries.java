package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Connection metadata for consumers outside the owning module. Never includes credentials. */
public interface ConnectionQueries {
    List<WebhookRoute> findActiveOutlineRoutes();

    boolean hasActiveSourceInstance(long workspaceId, IntegrationKind kind, String origin);

    List<ConnectionView> findByWorkspaceId(long workspaceId);

    List<Long> findWorkspaceIdsByKindAndState(IntegrationKind kind, IntegrationState state);

    Optional<ConnectionSnapshot> findActive(long workspaceId, IntegrationKind kind);

    Optional<ConnectionConfig> findActiveOrSuspendedConfig(long workspaceId, IntegrationKind kind);

    boolean hasConnectionInState(long workspaceId, IntegrationKind kind, IntegrationState state);

    Optional<ConnectionView> findReferenced(IntegrationRef ref);

    record ConnectionSnapshot(long id, long version, ConnectionConfig config) {}

    record ConnectionView(
            long id,
            IntegrationKind kind,
            IntegrationState state,
            ConnectionConfig config,
            @Nullable String instanceKey,
            @Nullable String displayName) {}

    record WebhookRoute(long workspaceId, @Nullable String route) {}
}
