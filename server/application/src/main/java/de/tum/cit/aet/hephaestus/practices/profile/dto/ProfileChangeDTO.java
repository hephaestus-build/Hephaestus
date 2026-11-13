package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ProfileChangeDirection;
import de.tum.cit.aet.hephaestus.practices.spi.ProfileChangeResolvedBy;
import de.tum.cit.aet.hephaestus.practices.spi.ProfileChangeType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ProfileChangeDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProfileChangeType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant at,

        @Nullable String practiceSlug,

        @Nullable String practiceName,

        @Nullable String groupSlug,

        @Nullable String groupName,

        @Nullable String from,

        @Nullable String to,

        @Nullable ProfileChangeDirection direction,

        @Nullable UUID feedbackId,

        @Nullable ProfileChangeResolvedBy resolvedBy,

        @Nullable Integer cleanNeeded,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ItemRefDTO> evidence) {
    public ProfileChangeDTO {
        evidence = List.copyOf(evidence);
    }
}
