package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read projection; writes keep the repair entity and its version. */
public record ProviderResourceRepairView(
        UUID id,
        String identityHash,
        IntegrationKind kind,
        String origin,
        ResourceType resourceType,
        String parentId,
        String resourceId,
        @Nullable String ownershipHash,
        ProviderResourceRepair.State state,
        Instant createdAt) {
    public Resource resource() {
        return new Resource(kind, origin, resourceType, parentId, resourceId, ownershipHash);
    }
}
