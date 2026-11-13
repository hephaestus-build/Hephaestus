package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Connection metadata for consumers outside the owning module. Never includes credentials. */
public interface ConnectionQueries {
    List<WebhookRoute> findActiveOutlineRoutes();

    boolean hasActiveSourceInstance(long workspaceId, IntegrationKind kind, String origin);

    record WebhookRoute(long workspaceId, @Nullable String route) {}
}
