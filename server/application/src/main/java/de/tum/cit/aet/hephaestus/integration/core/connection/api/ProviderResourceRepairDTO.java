package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepair.State;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairView;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** A repair's provider identity is pseudonymous security data, never an authorization credential. */
public record ProviderResourceRepairDTO(
        @NonNull UUID id,
        @NonNull String identityHash,
        @NonNull IntegrationKind kind,
        @NonNull String origin,
        @NonNull ResourceType resourceType,
        @NonNull String parentId,
        @NonNull String resourceId,
        @NonNull State state,
        @NonNull Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replacementInUse,
        @NonNull List<OwnerDTO> owners) {
    public record OwnerDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long connectionId,

            @NonNull String workspaceSlug) {}

    public static ProviderResourceRepairDTO from(
            ProviderResourceRepairView repair, List<OwnerDTO> owners, boolean replacementInUse) {
        var resource = repair.resource();
        return new ProviderResourceRepairDTO(
                repair.id(),
                repair.identityHash(),
                repair.kind(),
                resource.origin(),
                resource.type(),
                resource.parentId(),
                resource.resourceId(),
                repair.state(),
                repair.createdAt(),
                replacementInUse,
                owners);
    }
}
